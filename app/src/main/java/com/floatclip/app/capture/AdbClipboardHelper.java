package com.floatclip.app.capture;

import android.annotation.SuppressLint;
import android.content.ClipData;
import java.net.Socket;
import java.net.InetSocketAddress;
import com.floatclip.share.TransportCrypto;
import java.util.regex.Pattern;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optional Android 11 shell companion. Run explicitly through authorized ADB.
 * No network listener, stored clipboard, log content, or automatic privilege escalation.
 */
@SuppressLint({"PrivateApi","DiscouragedPrivateApi"})
public final class AdbClipboardHelper {

    private static final int MAGIC=0x46434331, MAX_BYTES=64000;
    private final int appUid;
    private final Object clipboard;
    private final Method read;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Socket socket;
    private volatile DataOutputStream output;
    private String lastStamp="";
    private boolean reportedRead=false;
    private Object listener;

    private AdbClipboardHelper(int appUid)throws Exception {
        this.appUid=appUid;
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"clipboard");
        clipboard=Class.forName("android.content.IClipboard$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Class<?> api=Class.forName("android.content.IClipboard");
        read=api.getMethod("getPrimaryClip",String.class,int.class);
        Class<?> observer=Class.forName("android.content.IOnPrimaryClipChangedListener");
        Binder callback=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("android.content.IOnPrimaryClipChangedListener");return true;}
                if(code==IBinder.FIRST_CALL_TRANSACTION){
                    data.enforceInterface("android.content.IOnPrimaryClipChangedListener");
                    main.post(AdbClipboardHelper.this::capture);return true;
                }
                return false;
            }
        };
        listener=Proxy.newProxyInstance(observer.getClassLoader(),new Class<?>[]{observer},(proxy,method,args)->{
            if(method.getName().equals("asBinder"))return callback;
            if(method.getName().equals("toString"))return "FloatClipClipboardObserver";
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            return null;
        });
        api.getMethod("addPrimaryClipChangedListener",observer,String.class,int.class).invoke(clipboard,listener,"com.android.shell",0);
    }
    private void capture(){
        DataOutputStream target=output;
        if(target==null)return;
        try{
            ClipData clip=(ClipData)read.invoke(clipboard,"com.android.shell",0);
            if(!reportedRead){System.out.println("Capture readable="+(clip!=null));reportedRead=true;}
            if(clip==null||clip.getItemCount()==0)return;
            // Received writes are marked by the app; never turn them into a local echo.
            if(clip.getDescription().getExtras()!=null&&clip.getDescription().getExtras().getString("com.floatclip.app.OWN_WRITE")!=null)return;
            CharSequence raw=clip.getItemAt(0).getText();
            String text=raw==null?"":raw.toString();
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            boolean excluded=text.trim().isEmpty()||bytes.length>MAX_BYTES||
                (clip.getDescription().getExtras()!=null&&clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false));
            String stamp=stamp(clip);
            if(stamp.equals(lastStamp))return;
            synchronized(target){
                if(target!=output)return;
                target.writeUTF(stamp);target.writeBoolean(excluded);
                target.writeInt(excluded?0:bytes.length);
                if(!excluded)target.write(bytes);
                target.flush();lastStamp=stamp;
            }
        }catch(Exception failure){Socket current=socket;if(current!=null)try{current.close();}catch(IOException ignored){}}
    }
    private static String stamp(ClipData clip)throws Exception{
        CharSequence raw=clip.getItemCount()>0?clip.getItemAt(0).getText():null;
        byte[] bytes=(raw==null?"":raw.toString()).getBytes(StandardCharsets.UTF_8);
        StringBuilder hex=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format("%02x",b&255));
        return clip.getDescription().getTimestamp()+":"+hex;
    }
    private void connectLoop(){
        int retryMs=1000;String previousFailure="";
        for(;;){
            Socket current=new Socket();
            try{
                // content(1) talks to the provider as UID 2000; no credential is placed in argv, files or logs.
                java.lang.Process bootstrap=new ProcessBuilder("/system/bin/content","call","--uri",
                    "content://com.floatclip.probe.capture","--method","session").redirectErrorStream(true).start();
                ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] block=new byte[1024];int size;
                try(InputStream response=bootstrap.getInputStream()){
                    while((size=response.read(block))!=-1){if(result.size()+size>4096)throw new IOException("Bootstrap response too large");result.write(block,0,size);}
                }
                bootstrap.waitFor();
                String session=result.toString("UTF-8");
                if(session.contains("state=disabled"))System.exit(0);
                java.util.regex.Matcher port=Pattern.compile("port=([0-9]{1,5})").matcher(session);
                java.util.regex.Matcher key=Pattern.compile("token=([a-f0-9]{64})").matcher(session);
                java.util.regex.Matcher uid=Pattern.compile("uid=([0-9]+)").matcher(session);
                if(!port.find()||!key.find()||!uid.find()||Integer.parseInt(uid.group(1))!=appUid)throw new IOException("Capture app is paused or not ready");
                byte[] token=new byte[32];String hex=key.group(1);
                for(int i=0;i<32;i++)token[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
                current.connect(new InetSocketAddress("127.0.0.1",Integer.parseInt(port.group(1))),3000);
                current.setSoTimeout(3000);
                TransportCrypto.SecureStreams secure=TransportCrypto.connect(current.getInputStream(),current.getOutputStream(),token);
                DataOutputStream send=new DataOutputStream(secure.output);
                DataInputStream receive=new DataInputStream(secure.input);
                if(receive.readInt()!=MAGIC)throw new IOException("Unsupported capture bridge");
                current.setSoTimeout(0);
                ClipData baseline=(ClipData)read.invoke(clipboard,"com.android.shell",0);
                lastStamp=baseline==null?"":stamp(baseline);
                socket=current;output=send;retryMs=1000;previousFailure="";
                System.out.println("FloatClip capture bridge connected");
                // Do not replay clipboard content predating this connection/consent.
                while(receive.read()!=-1){}
            }catch(Exception problem){
                String failure=problem.getClass().getSimpleName();
                if(!failure.equals(previousFailure)){System.err.println("Capture bridge waiting: "+failure);previousFailure=failure;}
            }
            finally{
                output=null;socket=null;
                try{current.close();}catch(IOException ignored){}
            }
            try{Thread.sleep(retryMs);retryMs=Math.min(10000,retryMs*2);}catch(InterruptedException stop){return;}
        }
    }
    public static void main(String[] args)throws Exception{
        if(Process.myUid()!=2000||(args.length!=1&&args.length!=2))throw new SecurityException("Start using authorized ADB shell");
        int appUid=Integer.parseInt(args[0]);
        if(appUid<10000)throw new SecurityException("Expected application UID");
        Looper.prepareMainLooper();
        AdbClipboardHelper helper=new AdbClipboardHelper(appUid);
        if(args.length==2){
            if(!args[1].equals("--self-test-copy"))throw new IllegalArgumentException("Unknown test");
            String marker="FC-QA-ADB-BACKGROUND-"+System.currentTimeMillis();
            Class.forName("android.content.IClipboard").getMethod("setPrimaryClip",ClipData.class,String.class,int.class)
                .invoke(helper.clipboard,ClipData.newPlainText("FloatClip QA",marker),"com.android.shell",0);
            ClipData check=(ClipData)helper.read.invoke(helper.clipboard,"com.android.shell",0);
            System.out.println(marker+" shellCanRead="+(check!=null&&check.getItemCount()>0));return;
        }
        new Thread(helper::connectLoop,"floatclip-capture-bridge").start();
        System.out.println("FloatClip ADB capture ready (no clipboard content is logged)");
        Looper.loop();
    }
}
