// A non-voting staging receiver: incomplete imports never become published data.
const fs=require('fs'),crypto=require('crypto');
const [source,dest,slow='0']=process.argv.slice(2);
(async()=>{const data=fs.readFileSync(source),hash=crypto.createHash('sha256').update(data).digest('hex');const fd=fs.openSync(dest+'.part','w');for(let i=0;i<data.length;i+=256){fs.writeSync(fd,data.subarray(i,i+256));if(+slow)await new Promise(r=>setTimeout(r,+slow));}fs.fsyncSync(fd);fs.closeSync(fd);if(crypto.createHash('sha256').update(fs.readFileSync(dest+'.part')).digest('hex')!==hash)throw Error('checksum');fs.renameSync(dest+'.part',dest);console.log(hash);})();
