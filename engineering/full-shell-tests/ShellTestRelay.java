import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.DataInputStream;
import me.timschneeberger.rootlessjamesdsp.audio.ShellRoutingWorker;
/** Disposable emulator ONLY. Not included in the application APK. */
public final class ShellTestRelay {
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException();
        int allowedUid=Integer.parseInt(args[1]);
        LocalServerSocket server=new LocalServerSocket(args[0]);
        System.out.println("RELAY_READY "+args[0]); System.out.flush();
        LocalSocket socket=server.accept();
        server.close();
        DataInputStream in=new DataInputStream(socket.getInputStream());
        int rate=in.readInt(), mode=in.readInt(), uid=in.readInt();
        if(uid!=allowedUid) throw new SecurityException("Synthetic UID only");
        String[] workerArgs=mode==1?new String[]{"16000","monitor",Integer.toString(uid)}:new String[]{Integer.toString(rate),Integer.toString(uid)};
        ShellRoutingWorker.run(workerArgs,in,socket.getOutputStream());
    }
}
