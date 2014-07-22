package scheduling.snooze;

import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import scheduling.snooze.msg.SnoozeMsg;
import scheduling.snooze.msg.TestFailGLMsg;
import scheduling.snooze.msg.TestFailGMMsg;

import java.util.ArrayList;

/**
 * Created by sudholt on 20/07/2014.
 */
public class Test extends Process {
    static String name;
    static Host host;
    static String inbox;
    public boolean testsToBeTerminated = false;

    static Multicast multicast;
    static GroupLeader gl;
    static ArrayList<GroupManager> gms = new ArrayList<>();
    static ArrayList<LocalController> lcs = new ArrayList<>();


    static String gm = "";
    static SnoozeMsg m = null;

    public Test(Host host, String name) {
        super(host, name);
        this.host = host;
        this.name = name;
        this.inbox = "test";
    }

    @Override
    public void main(String[] strings) throws MsgException {
        sleep(5000);
        procFailGLs();
        procTerminateGMs();
        procAddLCs();
        while (!testsToBeTerminated) {
            dispInfo();
            sleep(1000);
        }
    }

    static void dispInfo() {
        if (multicast.gmInfo.isEmpty()) {
            Logger.info("[Test.dispGMLCInfo] MUL.gmInfo empty");
            return;
        }
        int i = 0, al = 0;
        Logger.info("[Test.dispInfo]");
        for (String gm : multicast.gmInfo.keySet()) {
            int l = 0;
            for (String lc : multicast.lcInfo.keySet()) if (multicast.lcInfo.get(lc).gmHost.equals(gm)) l++;
            String gmLeader = "";
            for (GroupManager gmo: gms) if (gmo.host.getName().equals(gm)) gmLeader = gmo.glHostname;
            Logger.info("    Multicast: GM: " + gm + ", #LCs: " + l + ", leader: " + gmLeader);
            i++; al += l;
        }
        Logger.info("    GL: " + gl.host.getName()
                + ", #GM: " + gl.gmInfo.size() + ", #LCs: " + al + ", #Test.gms " + Test.gms.size() + "\n");
    }

    void procAddLCs() throws HostNotFoundException {
        new Process(host, host.getName() + "-addLCs") {
            public void main(String[] args) throws HostFailureException, HostNotFoundException, NativeException {
                sleep(5000);
                int lcNo = 21; // no. of statically allocated LCs
//                while (!testsToBeTerminated) {
                for (int i=0; i<20; i++) {
                    String[] lcArgs = new String[] {"node"+lcNo, "dynLocalController-"+lcNo};
                    LocalController lc =
                            new LocalController(Host.getByName("node"+lcNo), "dynLocalController-"+lcNo, lcArgs);
                    lc.start();
                    Logger.info("[Test.procAddLCs] Dyn. LC added: " + lcArgs[1]);
                    lcNo++;
                    sleep(5000);
                }
                sleep(AUX.DefaultComputeInterval);
            }
        }.start();
    }

    void procFailGLs() throws HostNotFoundException {
        new Process(host, host.getName() + "-terminateGMs") {
            public void main(String[] args) throws HostFailureException {
                sleep(5000);
                while (!testsToBeTerminated) {
                    if (multicast.gmInfo.size() < 3) {
                        Logger.info("[Test.terminateGLs] #GMs: " + multicast.gmInfo.size());
                        sleep(10000);
                        continue;
                    }
                    m = new TestFailGLMsg(name, AUX.glInbox(multicast.glHostname), null, null);
                    m.send();
                    Logger.info("[Test.terminateGMs] GL failure: " + Test.gl.getHost().getName());
                    sleep(10000);
                }
            }
        }.start();
    }

    void procTerminateGMs() throws HostNotFoundException {
        new Process(host, host.getName() + "-terminateGMs") {
            public void main(String[] args) throws HostFailureException {
                sleep(5000);
                while (!testsToBeTerminated) {
                    if (multicast.gmInfo.size() < 3) {
                        Logger.info("[Test.terminateGMs] #GMs: " + multicast.gmInfo.size());
                        sleep(10000);
                        continue;
                    }
                    gm = new ArrayList<String>(multicast.gmInfo.keySet()).get(0);
                    m = new TestFailGMMsg(name, AUX.gmInbox(gm), null, null);
                    m.send();
                    Logger.info("[Test.terminateGMs] Term. GM: " + gm + ", #GMs: " + multicast.gmInfo.size());
                    sleep(10000);
                }
            }
        }.start();
    }
}