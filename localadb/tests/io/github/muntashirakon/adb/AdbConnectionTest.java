package io.github.muntashirakon.adb;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AdbConnectionTest {
    private static int assertions;
    private static KeyPair keys;
    public static void main(String[] args) throws Exception {
        java.security.KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        keys = new KeyPair(pair.getPrivate(), new Certificate("X.509") {
            public byte[] getEncoded() { return pair.getPublic().getEncoded(); }
            public void verify(PublicKey key) { }
            public void verify(PublicKey key, String provider) { }
            public String toString() { return "test certificate"; }
            public PublicKey getPublicKey() { return pair.getPublic(); }
        });
        closeUnblocks();
        negotiationTimeout();
        readyBeforeWait();
        rejectsInvalidPeerLimit();
        byte[] open = AdbProtocol.generateOpen(1, "shell:echo \u00e9");
        AdbProtocol.Message parsed = AdbProtocol.Message.parse(new ByteArrayInputStream(open), AdbProtocol.A_VERSION_SKIP_CHECKSUM, 1024);
        check(parsed.payload.length == "shell:echo \u00e9".getBytes(StandardCharsets.UTF_8).length + 1, "UTF-8 byte allocation");
        byte[] oversized = AdbProtocol.generateMessage(AdbProtocol.A_WRTE, 1, 1, new byte[1025]);
        rejects(() -> AdbProtocol.Message.parse(new ByteArrayInputStream(oversized), AdbProtocol.A_VERSION_SKIP_CHECKSUM, 1024), "packet bound before allocation");
        rejects(() -> AdbProtocol.Message.parse(new InputStream() { public int read() { return 0; } public int read(byte[] b,int o,int l) { return 0; } }, AdbProtocol.A_VERSION_SKIP_CHECKSUM,1024), "no progress header");
        System.out.println("AdbConnectionTest: " + assertions + " assertions passed");
    }
    private static void closeUnblocks() throws Exception {
        try (Peer peer = new Peer((in, out) -> { while (in.read() >= 0) { } })) {
            AdbConnection connection = connection(peer.port());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiting = new Thread(() -> { try { connection.connect(30000,TimeUnit.MILLISECONDS,true); } catch (Throwable expected) { failure.set(expected); } });
            waiting.start(); check(peer.accepted.await(1,TimeUnit.SECONDS), "fake daemon accepted");
            long start = System.nanoTime(); connection.close();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000, "close bounded");
            waiting.join(1500); check(!waiting.isAlive(), "close unblocks pending connect");
            check(!connection.isConnected() && !connection.isConnectionEstablished(), "closed state reconciled");
        }
    }
    private static void negotiationTimeout() throws Exception {
        try (Peer peer = new Peer((in,out) -> { while(in.read()>=0) { } })) {
            AdbConnection connection=connection(peer.port()); long start=System.nanoTime();
            check(!connection.connect(100,TimeUnit.MILLISECONDS,true), "negotiation returns timeout");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000, "negotiation deadline bounded"); connection.close();
        }
    }
    private static void readyBeforeWait() throws Exception {
        try (Peer peer = new Peer((in,out) -> {
            AdbProtocol.Message.parse(in,AdbProtocol.A_VERSION_SKIP_CHECKSUM,1048576);
            out.write(AdbProtocol.generateMessage(AdbProtocol.A_CNXN,AdbProtocol.A_VERSION_SKIP_CHECKSUM,1024,new byte[0])); out.flush();
            AdbProtocol.Message request=AdbProtocol.Message.parse(in,AdbProtocol.A_VERSION_SKIP_CHECKSUM,1048576);
            out.write(AdbProtocol.generateMessage(AdbProtocol.A_OKAY,19,request.arg0,new byte[0]));
            out.write(AdbProtocol.generateMessage(AdbProtocol.A_WRTE,19,request.arg0,"abc".getBytes(StandardCharsets.US_ASCII))); out.flush();
            while(in.read()>=0) { }
        })) {
            AdbConnection connection=connection(peer.port()); check(connection.connect(1000,TimeUnit.MILLISECONDS,true), "fake negotiation success");
            long start=System.nanoTime(); AdbStream stream=connection.open("shell:fixed");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000,"immediate OKAY cannot be lost");
            byte[] payload=new byte[3]; check(stream.read(payload,0,3)==3 && "abc".equals(new String(payload,StandardCharsets.US_ASCII)),"stream payload");
            Thread reader=new Thread(() -> { try { stream.read(new byte[1],0,1); } catch(IOException expected) { } }); reader.start();
            connection.close(); reader.join(1000); check(!reader.isAlive(),"close unblocks stream read");
        }
    }
    private static void rejectsInvalidPeerLimit() throws Exception {
        try(Peer peer=new Peer((in,out)->{
            AdbProtocol.Message.parse(in,AdbProtocol.A_VERSION_SKIP_CHECKSUM,1048576);
            out.write(AdbProtocol.generateMessage(AdbProtocol.A_CNXN,AdbProtocol.A_VERSION_SKIP_CHECKSUM,Integer.MAX_VALUE,new byte[0]));out.flush();
            while(in.read()>=0) { }
        })) {
            AdbConnection connection=connection(peer.port());
            try { connection.connect(1000,TimeUnit.MILLISECONDS,true); } catch(IOException expected) { }
            check(!connection.isConnectionEstablished(),"reject huge advertised payload limit"); connection.close();
        }
    }
    private static AdbConnection connection(int port) throws IOException { return new AdbConnection.Builder("127.0.0.1",port).setApi(35).setKeyPair(keys).build(); }
    interface Checked { void run() throws Exception; }
    private static void rejects(Checked action,String label) throws Exception { try {action.run();throw new AssertionError(label);}catch(IOException expected){assertions++;} }
    private static void check(boolean value,String label) {if(!value)throw new AssertionError(label);assertions++;}
    interface Handler {void run(InputStream in,OutputStream out)throws Exception;}
    private static final class Peer implements AutoCloseable {
        final ServerSocket server; final CountDownLatch accepted=new CountDownLatch(1); volatile Socket socket;
        Peer(Handler handler)throws IOException {
            server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            Thread thread=new Thread(()->{try(Socket connection=server.accept()){socket=connection;accepted.countDown();handler.run(connection.getInputStream(),connection.getOutputStream());}catch(Exception expected){}});
            thread.setDaemon(true);thread.start();
        }
        int port(){return server.getLocalPort();}
        public void close()throws IOException {server.close();if(socket!=null)socket.close();}
    }
}
