package scheduling.entropyBased.dvms2;

import configuration.SimulatorProperties;
import org.simgrid.msg.*;
import java.util.Random;

public class SGActor {

    static Random random = new Random(SimulatorProperties.getSeed());

    public SGNodeRef ref = null;

    public SGActor(SGNodeRef ref) {
        this.ref = ref;
    }

    public SGNodeRef self() {
        return ref;
    }

    public static int MSG_COUNT = 0;

    public void send(SGNodeRef node, Object message){

//        if(message != "checkTimeout" && message != "overloadingDetected") {
//            MSG_COUNT++;
//            System.out.println("MSG_COUNT["+ Msg.getClock()+"]: "+MSG_COUNT+" -> "+message+"@"+node);
//        }

        MsgForSG msg = new MsgForSG(message,
                node+"", ref.getName() ,null);
        msg.send();
    }
    public void forward(SGNodeRef dest, SGNodeRef origin, Object message){
        MsgForSG msg = new MsgForSG(message, dest+"",
                origin+"",null);
        msg.send();
    }

    public Object ask(SGNodeRef node, Object message){
        MsgForSG msg = new MsgForSG(message,
                node+"", ref.getName(), Host.currentHost().getName()+":"+ random.nextGaussian());
        msg.send();
        MsgForSG reply;
        try {
            reply = (MsgForSG) Task.receive(msg.getReplyBox());
            return reply.getMessage();
        } catch (TransferFailureException e) {
            e.printStackTrace();
        } catch (HostFailureException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }
        return null;
    }
}	

