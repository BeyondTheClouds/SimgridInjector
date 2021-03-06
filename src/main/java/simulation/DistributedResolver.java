package simulation;


import org.simgrid.msg.*;
import org.simgrid.msg.Process;
import scheduling.distributed.dvms2.*;
import scheduling.distributed.dvms2.overlay.SimpleOverlay;


/** This class is in charge of launching the latest version of DVMs (currently DVMS V2 implemented in SCALA)
* @author Jonathan Pastor
*/
public class DistributedResolver extends Process {

    private String name;

    DistributedResolver(Host host, String name, String[] args) throws HostNotFoundException {
        super(host, name, args);
        this.name = name;
    }

    private void launchInstance(
            String nodeId, int nbCPUs, int cpuCapacity, int memoryTotal,//Information for DVMSNode
            int port,//Information for associated DVMSServer
            String neighborHostname, int neighborPort){//Information for neighbor DVMSServer

        Host host = Host.currentHost();

        try {

            SimulatorManager.setSchedulerActive(true);

            TimeoutSnoozerProcess timeoutSnoozer = new TimeoutSnoozerProcess(this.getHost(), name, nodeId, port);
            timeoutSnoozer.start();

            // TODO change here
            EntropyProcess entropyProcess = new EntropyProcess(this.getHost(), name, nodeId, port);
            entropyProcess.start();

            DVMSProcess dmvsProcess = new DVMSProcess(this.getHost(), name, nodeId, port, entropyProcess.self(), timeoutSnoozer.self());
            dmvsProcess.start();

            MonitorProcess monitorProcess = new MonitorProcess(SimulatorManager.getXHostByName(host.getName()), nodeId, port, dmvsProcess.self(), dmvsProcess);
            monitorProcess.start();

            TimeoutCheckerProcess timeoutProcess = new TimeoutCheckerProcess(SimulatorManager.getXHostByName(host.getName()), nodeId, port, dmvsProcess.self(), dmvsProcess);
            timeoutProcess.start();

            Msg.info("Agent "+nodeId+" started");

            // Register in the Overlay the current nodeRef
            SimpleOverlay.register(nodeId, dmvsProcess.self(), this);

            while (!SimulatorManager.isEndOfInjection()) {

                waitFor(3);

            }
            waitFor(3);
            SimulatorManager.setSchedulerActive(false);

        } catch (Exception e){
            e.printStackTrace();
        }

    }


    /**
     * @param args
     */
    public void main(String[] args) throws MsgException{
        if(args.length != 7){
            System.out.println("7 parameters required:");
            System.out.println("String nodeId, int nbCPUs, int cpuCapacity, int memoryTotal,\n" +
                    "int port,\n" +
                    "String neighborHostname, int neighborPort,\n");
            System.exit(1);
        }

        else{
            //Information for DVMSNode
            String nodeId = args[0];
            int nbCPUs = Integer.parseInt(args[1]);
            int cpuCapacity = Integer.parseInt(args[2]);
            int memoryTotal = Integer.parseInt(args[3]);

            //Information for associated DVMSServer
            int port = Integer.parseInt(args[4]);

            //Information for neighbor DVMSServer
            String neighborHostname = args[5];
            int neighborPort = Integer.parseInt(args[6]);

            //Create the msg Processes: the monitor and the communicator.
            launchInstance(
                    nodeId, nbCPUs, cpuCapacity, memoryTotal,
                    port,
                    neighborHostname, neighborPort);
        }
    }
}
