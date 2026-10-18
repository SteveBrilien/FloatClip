package com.floatclip.probe;
import android.app.*;
import android.os.Bundle;
import com.floatclip.share.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Generated-data integration harness. It never opens the system clipboard or user documents. */
public final class ShareInstrumentation extends Instrumentation {
    private LanShare transport; private ShareSession session; private File source;
    private byte[] expected; private String ip;
    private final AtomicBoolean ended=new AtomicBoolean(); private int checks;
    private static final String WINDOWS="FloatClip-Windows\n中文 📋\n\tcode  \r\n",ANDROID="FloatClip-Android\n中文 📋\n\tcode  \r\n";
    @Override public void onCreate(Bundle args) { super.onCreate(args); ip=args.getString("address",""); start(); }
    private synchronized void checked() { checks++; if(checks==5) finishResult(true,"two-way text, two-way 1048593-byte files, refusal"); }
    private void finishResult(boolean pass,String detail) {
        if(!ended.compareAndSet(false,true)) return;
        if(transport!=null) transport.close();
        if(source!=null) source.delete();
        Bundle result=new Bundle(); result.putString("result",pass ? "PASS" : "FAIL"); result.putString("detail",detail);
        finish(pass ? Activity.RESULT_OK : Activity.RESULT_CANCELED,result);
    }
    @Override public void onStart() {
        try {
            byte[] bytes=new byte[1048593]; for(int i=0;i<bytes.length;i++) bytes[i]=(byte)(i*31+7); expected=ShareWire.digest(bytes);
            File cache=new File(getTargetContext().getCacheDir(),"share-checks"); cache.mkdirs();
            source=new File(cache,"source.bin"); try(OutputStream out=new FileOutputStream(source)) { out.write(bytes); }
            transport=new LanShare(cache,new LanShare.Events() {
                public void invitation(String code) { Bundle b=new Bundle(); b.putString("invitation",code); sendStatus(10,b); }
                public void connected(ShareSession value) { session=value; try { value.sendText(ANDROID); } catch(Exception e) { finishResult(false,"initial send"); } }
                public void stopped(String message) { finishResult(false,message); }
            },new ShareSession.Events() {
                public void text(UUID id,String value,boolean outgoing) { if(value.equals(outgoing ? ANDROID : WINDOWS)) checked(); else finishResult(false,"text mismatch"); }
                public void offered(ShareWire.Offer offer) {
                    try {
                        if(offer.name.equals("FloatClip-reject.bin") && offer.size==0) { session.reject(offer.id); checked(); }
                        else if(offer.name.equals("FloatClip-Windows.bin") && offer.size==1048593 && java.security.MessageDigest.isEqual(offer.hash,expected)) session.accept(offer.id);
                        else finishResult(false,"unexpected offer");
                    } catch(Exception e) { finishResult(false,"offer decision"); }
                }
                public void progress(UUID id,String name,long done,long total,boolean outgoing) { }
                public void file(ShareWire.Offer offer,File local,boolean outgoing) {
                    try {
                        java.security.MessageDigest hash=ShareWire.sha(); byte[] buffer=new byte[32768];
                        try(InputStream input=new FileInputStream(local)) { int n; while((n=input.read(buffer))!=-1) hash.update(buffer,0,n); }
                        if(!java.security.MessageDigest.isEqual(hash.digest(),expected)) { finishResult(false,"file mismatch"); return; }
                        if(!outgoing) { local.delete(); session.sendFile(source,"FloatClip-Android.bin"); }
                        checked();
                    } catch(Exception e) { finishResult(false,"file callback"); }
                }
                public void notice(String message) { }
                public void closed() { if(!ended.get()) finishResult(false,"session closed early"); }
            });
            transport.listen(ip);
        } catch(Exception e) { finishResult(false,"harness setup"); }
    }
}
