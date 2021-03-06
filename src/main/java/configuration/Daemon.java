/**
 * Copyright 2012-2013-2014. The SimGrid Team. All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the license (GNU LGPL) which comes with this package.
 *
 * This class implement the daemon process that simulates the load for each VM.
 * The daemon is a simple Simgrid MSG Process that runs tasks during the whole execution of the simulation.
 *
 * Please note that the daemon is running as fast as possible: The load of the VM is controlled directly by invoking
 * setBound from the XVM class.
 *
 * @author: adrien.lebre@inria.fr
 */

package configuration;


import org.simgrid.msg.*;

import org.simgrid.msg.Process;
import simulation.SimulatorManager;

public class Daemon extends Process {

    /**
     * A reference to the on-going task that simulates the current load of the VM
     */
	private Task currentTask;

    static private int iDaemon = 0;
    private int id = 0;


    /**
     * Constructor
     * @param host the name of the host on which the task is running (in our case, the host is a VM)
     * @param load the load
     */
    public Daemon(Host host, int load) {
		super(host,"Daemon-" + host.getName());
        id = iDaemon++;
        // Creation of the task
        //   The load is a dummy computation of the speed of VM * 100
        // TODO please confirm whether getHost().getSpeed() returns the speed of one core or the speed of the sum of each core
        currentTask = new Task(this.getHost().getName()+"-daemon-0", this.getHost().getSpeed()*100.0, 0);
        n_daemon++;
    }
    public void main(String[] args) throws MsgException {
        int i = 1;

        while(!SimulatorManager.isEndOfInjection()) {
            try {
                currentTask.execute();
            } catch (HostFailureException e) {
                e.printStackTrace();
            } catch (TaskCancelledException e) {
                e.printStackTrace();
                suspend(); // Suspend the process
            }
            currentTask = new Task(this.getHost().getName()+"-daemon-"+(i++), this.getHost().getSpeed()*100.0, 0);
            // TODO test whether the CPU consumption is higher when putting larger tasks (i.e. 1000000000000.0 for instance).
        }
    }

    public double getRemaining(){
        return this.currentTask.getFlopsAmount();
    }

    public static int n_daemon = 0;

    public void kill() {n_daemon--;
        super.kill();
    }
}
