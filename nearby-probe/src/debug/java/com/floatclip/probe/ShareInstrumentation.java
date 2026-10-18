package com.floatclip.probe;
import android.app.*;
import android.os.Bundle;
import com.floatclip.share.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Generated-data integration harness. It never opens the system clipboard or user documents. */
public final class ShareInstrumentation extends Instrumentation {
    private LanShare transport; private LanPair pairTransport; private String pairing="",verification=""; private ShareSession session; private File source;
    private byte[] expected; private String ip;
    private final AtomicBoolean ended=new AtomicBoolean(); private int checks;
    private static final String WINDOWS="FloatClip-Windows\n中文 📋\n\tcode  \r\n",ANDROID="FloatClip-Android\n中文 📋\n\tcode  \r\n";
    @Override public void onCreate(Bundle args) { super.onCreate(args); ip=args.getString("address",""); pairing=args.getString("pairing",""); start(); }
    private synchronized void checked() { checks++; Bundle progress=new Bundle(); progress.putInt("checks",checks); sendStatus(20,progress); if(checks==5) finishResult(true,"two-way text, two-way 1048593-byte files, refusal"); }
    private void finishResult(boolean pass,String detail) {
        if(!ended.compareAndSet(false,true)) return;
        if(transport!=null) transport.close(); if(pairTransport!=null) pairTransport.close();
        if(source!=null) source.delete();
        Bundle result=new Bundle(); result.putString("result",pass ? "PASS" : "FAIL"); result.putString("detail",detail); result.putString("verificationCode",verification);
        finish(pass ? Activity.RESULT_OK : Activity.RESULT_CANCELED,result);
    }
    @android.annotation.SuppressLint("ApplySharedPref") // The QA credential reset must finish before accepting a connection.
    @Override public void onStart() {
        try {
            byte[] bytes=new byte[1048593]; for(int i=0;i<bytes.length;i++) bytes[i]=(byte)(i*31+7); expected=ShareWire.digest(bytes);
            File cache=new File(getTargetContext().getCacheDir(),"share-checks"); cache.mkdirs();
            source=new File(cache,"source.bin"); try(OutputStream out=new FileOutputStream(source)) { out.write(bytes); }
            ShareSession.Events messages=new ShareSession.Events() {
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
                public void notice(String message) { Bundle b=new Bundle(); b.putString("notice",message); sendStatus(20,b); }
                public void failure(Exception problem) { Bundle b=new Bundle(); b.putString("failure",problem.getClass().getSimpleName()+": "+problem.getMessage()); sendStatus(20,b); }
                public void closed() { if(!ended.get()) finishResult(false,"session closed early"); }
            };
            if(!pairing.isEmpty()) {
                android.content.SharedPreferences trust=getTargetContext().getSharedPreferences("share-pairs-qa",0);
                if(pairing.equals("fresh")) trust.edit().clear().commit();
                pairTransport=new LanPair(cache,trust,new LanPair.Events() {
                    public void available(String address) { Bundle b=new Bundle(); b.putString("ready",address); sendStatus(10,b); }
                    public void verify(String code,java.util.function.Consumer<Boolean> answer) { verification=code; answer.accept(true); }
                    public void connected(ShareSession value) { session=value; try { value.sendText(ANDROID); } catch(Exception e) { finishResult(false,"initial send"); } }
                    public void stopped(String message) { finishResult(false,message); }
                },messages); pairTransport.listen(ip);
            } else {
            transport=new LanShare(cache,new LanShare.Events() {
                public void invitation(String code) { Bundle b=new Bundle(); b.putString("invitation",code); sendStatus(10,b); }
                public void connected(ShareSession value) { session=value; try { value.sendText(ANDROID); } catch(Exception e) { finishResult(false,"initial send"); } }
                public void stopped(String message) { finishResult(false,message); }
            },messages);
            transport.listen(ip);
            }
        } catch(Exception e) { finishResult(false,"harness setup"); }
    }
}
