package edu.baodex.campus;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Windows loopback TCP proxy. Classifies peer process via client port and netstat PID. */
public class LinkProxy implements AutoCloseable {
  final ServerSocket listener;
  final Map<Socket, Integer> sockets = new ConcurrentHashMap<>();
  final Set<Integer> blocked = ConcurrentHashMap.newKeySet();
  final int target;

  public LinkProxy(int target) throws IOException {
    this.target = target;
    listener = new ServerSocket(19000 + target, 50, InetAddress.getByName("127.0.0.1"));
    Thread accept =
        new Thread(
            () -> {
              while (!listener.isClosed())
                try {
                  Socket incoming = listener.accept();
                  Thread t = new Thread(() -> connect(incoming));
                  t.setDaemon(true);
                  t.start();
                } catch (IOException ignored) {
                }
            },
            "link-accept");
    accept.setDaemon(true);
    accept.start();
  }

  int source(Socket incoming) throws Exception {
    Process p =
        new ProcessBuilder("netstat", "-ano", "-p", "tcp").redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    for (String line : output.split("\\R")) {
      String[] c = line.trim().split("\\s+");
      if (c.length < 5
          || !c[1].equals("127.0.0.1:" + incoming.getPort())
          || !c[2].equals("127.0.0.1:" + (19000 + target))) continue;
      long pid = Long.parseLong(c[c.length - 1]);
      for (int i = 0; i < 3; i++) {
        var file = java.nio.file.Path.of("data/node-" + i + ".pid");
        if (java.nio.file.Files.exists(file)
            && Long.parseLong(java.nio.file.Files.readString(file).replace("\uFEFF", "").trim())
                == pid) {
          System.out.println("LINK source=n" + i + " target=n" + target + " pid=" + pid);
          return i;
        }
      }
      return -1;
    }
    throw new IOException("Cannot classify client port " + incoming.getPort());
  }

  void connect(Socket incoming) {
    try {
      int source = source(incoming);
      if (blocked.contains(source)) {
        incoming.close();
        return;
      }
      Socket outgoing = new Socket("127.0.0.1", 19300 + target);
      sockets.put(incoming, source);
      sockets.put(outgoing, source);
      pipe(incoming, outgoing, source);
      pipe(outgoing, incoming, source);
    } catch (Exception e) {
      System.err.println("PROXY_CLASSIFY " + e);
      try {
        incoming.close();
      } catch (IOException ignored) {
      }
    }
  }

  void pipe(Socket from, Socket to, int source) {
    Thread t =
        new Thread(
            () -> {
              try {
                byte[] b = new byte[65536];
                int n;
                while ((n = from.getInputStream().read(b)) >= 0) {
                  if (blocked.contains(source)) break;
                  to.getOutputStream().write(b, 0, n);
                  to.getOutputStream().flush();
                }
              } catch (Exception ignored) {
              } finally {
                for (Socket s : List.of(from, to)) {
                  sockets.remove(s);
                  try {
                    s.close();
                  } catch (IOException ignored) {
                  }
                }
              }
            },
            "proxy-stream");
    t.setDaemon(true);
    t.start();
  }

  public synchronized void configure(int source, boolean block) {
    if (block) blocked.add(source);
    else blocked.remove(source);
    if (block)
      sockets.forEach(
          (s, owner) -> {
            if (owner == source)
              try {
                s.close();
              } catch (IOException ignored) {
              }
          });
  }

  public void close() throws IOException {
    listener.close();
    for (Socket s : sockets.keySet()) s.close();
  }
}
