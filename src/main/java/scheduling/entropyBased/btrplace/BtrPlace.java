package scheduling.entropyBased.btrplace;

import configuration.SimulatorProperties;
import configuration.XHost;
import configuration.XVM;
import org.btrplace.model.Mapping;
import org.btrplace.model.Model;
import org.btrplace.model.Node;
import org.btrplace.model.VM;
import org.btrplace.model.view.ShareableResource;
import org.btrplace.plan.ReconfigurationPlan;
import org.btrplace.plan.event.MigrateVM;
import org.btrplace.scheduler.SchedulerException;
import org.btrplace.scheduler.choco.DefaultChocoScheduler;
import org.btrplace.scheduler.choco.DefaultParameters;
import org.btrplace.scheduler.choco.duration.DurationEvaluators;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostFailureException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Process;
import scheduling.entropyBased.btrplace.comparators.VmNamesBasedActionComparator;
import scheduling.entropyBased.btrplace.configuration.Configuration;
import scheduling.entropyBased.common.AbstractScheduler;
import scheduling.entropyBased.common.SchedulerResult;
import simulation.SimulatorManager;
import trace.Trace;

import java.io.*;
import java.util.*;


/**
 * Scheduler reposant sur BtrPlace
 *
 * @author Hadrien Gerard
 * @version 1.0
 */
public class BtrPlace extends AbstractScheduler {

    private DefaultChocoScheduler scheduler;
    private Configuration configuration; // L'ensemble Modele + contraintes pour BtrPlace
    private ReconfigurationPlan reconfigurationPlan;
    private boolean abortRP;



    public void initialize(Collection<XHost> hostsToCheck, int loopID) {
        this.initialize(hostsToCheck);
        this.loopID = loopID;
    }

    public void initialize(Collection<XHost> hostsToCheck) {
        this.configuration = ExtractConfiguration(hostsToCheck);
        DefaultParameters ps = new DefaultParameters(); //TODO: permettre de passer les paramètres en paramètre
        this.scheduler = new DefaultChocoScheduler(ps);
        this.scheduler.doOptimize(true); // TODO: Voir si nous cherchons la meilleur solution possible (Voire passer la valeur en paramètre ?)
        this.scheduler.doRepair(true);
        this.scheduler.setTimeLimit(configuration.getModel().getMapping().getNbNodes() / 8); //TODO: Hadrien, 8 -> nombre de coeurs dispos ?
        this.scheduler.setDurationEvaluators(DurationEvaluators.newBundle()); //TODO: Hadrien, Vérifier cohérence
        this.abortRP = false;
        //Log the current Configuration
        //TODO Hadrien : Implementer la serialisation de la configuration BtrPlace
        /*try {
            String fileName = "logs/entropy/configuration/" + loopID + "-"+ System.currentTimeMillis() + ".txt";
            FileConfigurationSerializerFactory.getInstance().write(configuration, fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }*/
    }

    @Override
    public ComputingState computeReconfigurationPlan() {
        ComputingState res = ComputingState.NO_RECONFIGURATION_NEEDED;
        reconfigurationPlan = null;

        try {
            timeToComputeVMRP = System.currentTimeMillis();

            reconfigurationPlan = scheduler.solve(configuration.getModel(), configuration.getSatConstraints());//configuration.asInstance());

            timeToComputeVMRP = System.currentTimeMillis() - timeToComputeVMRP;
            /*System.out.println("Time-based plan:");
            System.out.println(new TimeBasedPlanApplier().toString(reconfigurationPlan));
            System.out.println("\nDependency based plan:");
            System.out.println(new DependencyBasedPlanApplier().toString(reconfigurationPlan));*/
        } catch (SchedulerException ex) {
            System.err.println(ex.getMessage());
            res = ComputingState.RECONFIGURATION_FAILED;
        }

        if (reconfigurationPlan != null) {
            if (reconfigurationPlan.getActions().isEmpty()) {
                res = ComputingState.NO_RECONFIGURATION_NEEDED;
            }
            reconfigurationPlanCost = reconfigurationPlan.getDuration();
            //configuration = new Instance(plan.getResult(), configuration.getSatConstraints(), configuration.getOptConstraint());
            //newModel = reconfigurationPlan.getResult();
            nbMigrations = computeNbMigrations();
            reconfigurationGraphDepth = computeReconfigurationGraphDepth();
            res = ComputingState.SUCCESS;
        }

        return res;
    }

    //Get the number of migrations
    private int computeNbMigrations(){
        int nbMigrations = 0;

        for (org.btrplace.plan.event.Action a : reconfigurationPlan.getActions()){
            if(a instanceof MigrateVM){
                nbMigrations++;
            }
        }

        return nbMigrations;
    }

    //Get the depth of the reconfiguration graph
    //May be compared to the number of steps in Entropy 1.1.1
    //Return 0 if there is no action, and (1 + maximum number of dependencies) otherwise
    private int computeReconfigurationGraphDepth(){
        if (reconfigurationPlan.getActions().isEmpty()) {
            return 0;

        } else {
            int maxNbDeps = 0;
            int nbDeps;
            Collection<org.btrplace.plan.event.Action> actions = reconfigurationPlan.getActions();
            for (org.btrplace.plan.event.Action action : actions) {
                nbDeps = reconfigurationPlan.getDirectDependencies(action).size(); //TODO: Hadrien, vérifier correspondance getDirectDependencies() <-> getUnsatisfiedDependencies()
                if (nbDeps > maxNbDeps) {
                    maxNbDeps = nbDeps;
                }
            }

            return 1 + maxNbDeps;
        }
    }

    @Override
    public void applyReconfigurationPlan() {
        if(reconfigurationPlan != null && !reconfigurationPlan.getActions().isEmpty()) {
            //Model model = plan.getReconfigurationApplier().apply(plan);
            //configuration = new Instance(model, configuration.getSatConstraints(), configuration.getOptConstraint());
            Comparator<org.btrplace.plan.event.Action> startFirstComparator = new VmNamesBasedActionComparator(configuration.getVmNames());
            LinkedList<org.btrplace.plan.event.Action> sortedActions = new LinkedList<>(reconfigurationPlan.getActions());
            Collections.sort(sortedActions, startFirstComparator);

            try {
                File file = new File("logs/entropy/reconfigurationplan/" + loopID + "-" + System.currentTimeMillis() + ".txt");
                file.getParentFile().mkdirs();
                PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)));
                pw.write(reconfigurationPlan.toString());
                /*for (org.btrplace.plan.event.Action a : sortedActions) {
                    pw.write(a.toString()+"\n");
                }*/
                pw.flush();
                pw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Apply the reconfiguration plan.
            try {
                applyReconfigurationPlanLogically(sortedActions);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }

    //Apply the reconfiguration plan logically (i.e. create/delete Java objects)
    private void applyReconfigurationPlanLogically(LinkedList<org.btrplace.plan.event.Action> sortedActions) throws InterruptedException{

        for (org.btrplace.plan.event.Action a : sortedActions) {
            applyReconfigurationPlanForAction(a);
        }

        // If you reach that line, it means that either the execution of the plan has been completely launched or the
        // plan has been aborted. In both cases, we should wait for the completion of on-going migrations

        // Add a watch dog to determine infinite loop
        int watchDog = 0;

        while(this.ongoingMigration()){
            //while(this.ongoingMigration() && !SimulatorManager.isEndOfInjection()){
            try {
                Process.getCurrentProcess().waitFor(1);
                watchDog ++;
                if (watchDog%100==0){
                    Msg.info("You're are waiting for a couple of seconds (already "+watchDog+" seconds)");
                    if(SimulatorManager.isEndOfInjection()){
                        Msg.info("Something wrong we are waiting too much, bye bye");
                        System.exit(-1);
                    }
                }
            } catch (HostFailureException e) {
                e.printStackTrace();
            }
        }
    }

    private void applyReconfigurationPlanForAction(org.btrplace.plan.event.Action a) throws InterruptedException {
        //Start the feasible actions
        // ie, actions with a start moment equals to 0.
        if ((a.getStart() == 0)  && !isReconfigurationPlanAborted()) {
            Set<org.btrplace.plan.event.Action> depedencies = reconfigurationPlan.getDirectDependencies(a);
            for(org.btrplace.plan.event.Action dep : depedencies) {
                applyReconfigurationPlanForAction(dep);
            }
            instantiateAndStart(a);
            a.apply(configuration.getModel());
        }
    }


    private void instantiateAndStart(org.btrplace.plan.event.Action a) throws InterruptedException{
        if(a instanceof MigrateVM){
            MigrateVM migration = (MigrateVM) a;
            this.relocateVM(configuration.getVmName(migration.getVM().id()),
                    configuration.getNodeName(migration.getSourceNode().id()),
                    configuration.getNodeName(migration.getDestinationNode().id()));
        } else{
            System.err.println("UNRECOGNIZED ACTION WHEN APPLYING THE RECONFIGURATION PLAN");
        }
    }



    /**
     * @param hostsToCheck
     * @return the duration of the reconfiguration (i.e. > 0), -1 there is no viable reconfiguration, -2 the reconfiguration crash
     */
    public SchedulerResult checkAndReconfigure(Collection<XHost> hostsToCheck) {

        long beginTimeOfCompute;
        long endTimeOfCompute;
        long computationTime;
        ComputingState computingState;
        long reconfigurationTime = 0;
        SchedulerResult enRes = new SchedulerResult();

		/* Tracing code */
        int i;
        for (XHost h : hostsToCheck) {
            if (!h.isViable())
                Trace.hostPushState(h.getName(), "PM", "violation-det");
            Trace.hostSetState(h.getName(), "SERVICE", "booked");
        }

        Msg.info("Launching scheduler (loopId = " + loopID + ") - start to compute");
        Msg.info("Nodes considered: " + configuration.getModel().getMapping().getAllNodes().toString());

        /** PLEASE NOTE THAT ALL COMPUTATIONS BELOW DOES NOT MOVE FORWARD THE MSG CLOCK ***/
        beginTimeOfCompute = System.currentTimeMillis();
        computingState = this.computeReconfigurationPlan();
        endTimeOfCompute = System.currentTimeMillis();
        computationTime = (endTimeOfCompute - beginTimeOfCompute);

        /* Tracing code */
        double computationTimeAsDouble = ((double) computationTime) / 1000;

        int migrationCount = 0;
        if(computingState.equals(ComputingState.SUCCESS)) {
            migrationCount = this.reconfigurationPlan.getSize();
        }

        int partitionSize = hostsToCheck.size();

        /** **** NOW LET'S GO BACK TO THE SIMGRID WORLD **** */

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "compute", String.format("{\"duration\" : %f, \"result\" : \"%s\", \"migration_count\": %d, \"psize\": %d}", computationTimeAsDouble, computingState, migrationCount, partitionSize));


        try {
            Process.sleep(computationTime); // instead of waitFor that takes into account only seconds
        } catch (HostFailureException e) {
            e.printStackTrace();
        }

        Msg.info("Computation time (in ms):" + computationTime);
        enRes.setDuration(computationTime);

        if (computingState.equals(ComputingState.NO_RECONFIGURATION_NEEDED)) {
            Msg.info("Configuration remains unchanged"); //res is already set to 0.
        } else if (computingState.equals(ComputingState.SUCCESS)) {

			/* Tracing code */
            // TODO Adrien -> Adrien, try to consider only the nodes that are impacted by the reconfiguration plan
            for (XHost h : hostsToCheck)
                Trace.hostSetState(h.getName(), "SERVICE", "reconfigure");

            Trace.hostPushState(Host.currentHost().getName(), "SERVICE", "reconfigure");


            Msg.info("Starting reconfiguration");
            double startReconfigurationTime = Msg.getClock();
            this.applyReconfigurationPlan();
            double endReconfigurationTime = Msg.getClock();
            reconfigurationTime = ((long) (endReconfigurationTime - startReconfigurationTime) * 1000);
            Msg.info("Reconfiguration time (in ms): " + reconfigurationTime);
            enRes.setDuration(enRes.getDuration() + reconfigurationTime);
            Msg.info("Number of nodes used: " + hostsToCheck.size());
            if (isReconfigurationPlanAborted())
                enRes.setRes(-2);
            else
                enRes.setRes(1);

            Trace.hostPopState(Host.currentHost().getName(), "SERVICE"); //PoP reconfigure;
        } else {
            Msg.info("Entropy did not find any viable solution");
            enRes.setRes(-1);
        }

		/* Tracing code */
        for (XHost h : hostsToCheck)
            Trace.hostSetState(h.getName(), "SERVICE", "free");

        Trace.hostSetState(Host.currentHost().getName(), "SERVICE", "free");
        return enRes;
    }

    // Create configuration for Entropy
    public static Configuration ExtractConfiguration(Collection<XHost> xhosts) {
        Configuration currConf = new Configuration();
        Model model = currConf.getModel();
        Mapping map = model.getMapping();
        ShareableResource rcMem = new ShareableResource("mem", SimulatorProperties.DEFAULT_MEMORY_TOTAL,
                SimulatorProperties.DEFAULT_VM_MEMORY_CONSUMPTION);
        ShareableResource rcCPU = new ShareableResource("cpu", SimulatorProperties.DEFAULT_CPU_CAPACITY,
                SimulatorProperties.DEFAULT_VMMAX_CPU_CONSUMPTION);
        model.attach(rcCPU);
        model.attach(rcMem);

        // Add nodes
        for (XHost tmpH:xhosts){
            // Consider only hosts that are turned on
            if (tmpH.isOff()) {
                System.err.println("WTF, you are asking me to analyze a dead node (" + tmpH.getName() + ")");
                //System.exit(-1);
            }

            Node n = model.newNode();
            currConf.setNodeName(n.id(), tmpH.getName());
            rcCPU.setCapacity(n,tmpH.getCPUCapacity());
            rcMem.setCapacity(n,tmpH.getMemSize());
            map.addOnlineNode(n);

            for (XVM tmpVM : tmpH.getRunnings()) {
                VM vm = model.newVM();
                currConf.setVmName(vm.id(), tmpVM.getName());
                rcCPU.setConsumption(vm, (int) tmpVM.getCPUDemand());
                rcMem.setConsumption(vm, tmpVM.getMemSize());
                map.addRunningVM(vm, n);
            }

        }

        return currConf;
    }

    private int ongoingMigration = 0 ;

    private void incMig(){
        this.ongoingMigration++ ;
        Trace.hostVariableAdd(SimulatorManager.getInjectorNodeName(), "NB_MIG", 1);
    }
    private void decMig() {
        this.ongoingMigration-- ;
    }

    private boolean ongoingMigration() {
        return (this.ongoingMigration != 0);
    }

    private void abortReconfigurationPlan() {this.abortRP = true;}

    private boolean isReconfigurationPlanAborted() {
        return this.abortRP;
    }

    public void relocateVM(final String vmName, final String sourceName, final String destName) {
        Random rand = new Random(SimulatorProperties.getSeed());

        Msg.info("Relocate VM " + vmName + " (from " + sourceName + " to " + destName + ")");

        if (destName != null) {
            String[] args = new String[3];

            args[0] = vmName;
            args[1] = sourceName;
            args[2] = destName;
            // Asynchronous migration
            // The process is launched on the source node
            try {
                new Process(Host.getByName(sourceName), "Migrate-" + rand.nextDouble(), args) {
                    public void main(String[] args) {
                        XHost destHost = null;
                        XHost sourceHost = null;

                        try {
                            sourceHost = SimulatorManager.getXHostByName(args[1]);
                            destHost = SimulatorManager.getXHostByName(args[2]);
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("You are trying to migrate from/to a non existing node");
                        }

                        if (destHost != null) {
                            if (!sourceHost.isOff() && !destHost.isOff()) {
                                incMig();

                                double timeStartingMigration = Msg.getClock();
                                Trace.hostPushState(vmName, "SERVICE", "migrate", String.format("{\"vm_name\": \"%s\", \"from\": \"%s\", \"to\": \"%s\"}", vmName, sourceName, destName));
                                int res = sourceHost.migrate(args[0], destHost);
                                // TODO, we should record the res of the migration operation in order to count for instance how many times a migration crashes ?
                                // To this aim, please extend the hostPopState API to add meta data information
                                Trace.hostPopState(vmName, "SERVICE", String.format("{\"vm_name\": \"%s\", \"result\": %d}", vmName, res));
                                double migrationDuration = Msg.getClock() - timeStartingMigration;

                                if (res == 0) {
                                    Msg.info("End of migration of VM " + args[0] + " from " + args[1] + " to " + args[2]);

                                    if (!destHost.isViable()) {
                                        Msg.info("ARTIFICIAL VIOLATION ON " + destHost.getName() + "\n");
                                        // If Trace.hostGetState(destHost.getName(), "PM").equals("normal")
                                        Trace.hostSetState(destHost.getName(), "PM", "violation-out");
                                    }
                                    if (sourceHost.isViable()) {
                                        Msg.info("SOLVED VIOLATION ON " + sourceHost.getName() + "\n");
                                        Trace.hostSetState(sourceHost.getName(), "PM", "normal");
                                    }

                                    /* Export that the migration has finished */
                                    Trace.hostSetState(vmName, "migration", "finished", String.format("{\"vm_name\": \"%s\", \"from\": \"%s\", \"to\": \"%s\", \"duration\": %f}", vmName, sourceName, destName, migrationDuration));
                                    Trace.hostPopState(vmName, "migration");
                                } else {

                                    Trace.hostSetState(vmName, "migration", "failed", String.format("{\"vm_name\": \"%s\", \"from\": \"%s\", \"to\": \"%s\", \"duration\": %f}", vmName, sourceName, destName, migrationDuration));
                                    Trace.hostPopState(vmName, "migration");

                                    Msg.info("Something was wrong during the migration of  " + args[0] + " from " + args[1] + " to " + args[2]);
                                    Msg.info("Reconfiguration plan cannot be completely applied so abort it");
                                    abortReconfigurationPlan();
                                }
                                decMig();
                            }
                        }

                    }
                }.start();

            } catch (Exception e) {
                e.printStackTrace();
            }

        } else {
            System.err.println("You are trying to relocate a VM on a non existing node");
            System.exit(-1);
        }
    }

}