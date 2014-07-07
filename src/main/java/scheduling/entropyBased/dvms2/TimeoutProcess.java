package scheduling.entropyBased.dvms2;

import configuration.XHost;
import org.simgrid.msg.HostFailureException;
import org.simgrid.msg.Process;
import simulation.SimulatorManager;

import java.util.concurrent.TimeoutException;

public class TimeoutProcess extends Process {

    public TimeoutActor timeoutActor;

    public TimeoutProcess(XHost xhost, String name, int port, SGNodeRef ref, DVMSProcess process) {
        super(xhost.getSGHost(), String.format("%s-checkout-checker", name, port));

        this.timeoutActor = new TimeoutActor(ref, xhost, process);
    }

    public class TimeoutActor extends SGActor {

        SGNodeRef ref;
        DVMSProcess process;
        XHost xhost;

        public TimeoutActor(SGNodeRef ref, XHost xhost, DVMSProcess process) {
            super(ref);

            this.ref = ref;
            this.xhost = xhost;
            this.process = process;
        }

        public void doCheckTimeout() throws HostFailureException {


            // Send a "checkTimeout" string instead of CheckTimeout()
            send(ref, "checkTimeout");
            waitFor(1);


        }
    }

    public void main(String args[]) {

        try {
            while (!SimulatorManager.isEndOfInjection()) {

                timeoutActor.doCheckTimeout();

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}


