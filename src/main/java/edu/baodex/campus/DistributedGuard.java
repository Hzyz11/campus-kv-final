package edu.baodex.campus;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("distributed")
public class DistributedGuard extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String path = req.getRequestURI();
    boolean allowed =
        path.matches("/api/auth/(login|logout|me)")
            || path.equals("/api/venues") && req.getMethod().equals("GET")
            || path.matches("/api/venues/\\d+/schedule") && req.getMethod().equals("GET")
            || path.matches("/api/bookings(/\\d+)?")
                && java.util.Set.of("GET", "POST").contains(req.getMethod())
            || path.matches("/api/bookings/\\d+/(approve|reject|cancel)")
                && req.getMethod().equals("POST")
            || path.matches("/api/requests/[A-Za-z0-9_-]+") && req.getMethod().equals("GET")
            || path.equals("/api/lab/init") && req.getMethod().equals("POST")
            || java.util.Set.of("/api/closures", "/api/audit").contains(path)
                && req.getMethod().equals("GET");
    allowed =
        allowed
            || java.util.Set.of("/api/lab/kv", "/api/lab/clock", "/api/lab/fault").contains(path)
                && req.getMethod().equals("POST")
            || path.equals("/api/lab/status") && req.getMethod().equals("GET");
    if (path.startsWith("/api/") && !allowed) {
      res.setStatus(405);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write("{\"message\":\"分布式模式仅开放预约核心功能\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
