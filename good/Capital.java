package eos.good;

import java.util.ArrayList;
import java.util.LinkedHashSet;

import eos.bank.Bank;
import eos.agent.firm.CFirm;
import eos.util.StdRandom;

/**
 * Capital stock
 * 
 * @author zhihongx
 * 
 */
public class Capital extends Good {

	// ID of the owner
	private int ownerID;

	/* a machine */
	private class Machine {

		// (per step) price of the machine at purchase time
		private double price;

		// number of steps the machine could be used for
		private int life;

		// remaining number of steps the machine could be used for
		private int remainingLife;

		// producer of this machine
		private CFirm producer;
	}

	// machines
	private LinkedHashSet<Machine> machines;

	// machines scrapped in the current step
	private ArrayList<Machine> scrappedMachines;

	/**
	 * Create a new capital stock initialized with <tt>quantity</tt> of
	 * machines. Each machine is assigned a random producer from
	 * <tt>producers</tt>. The remaining life of each machine is initialized to
	 * a random value between 0.5 * life and life.
	 * 
	 * @param quantity
	 *            initial quantity of machines
	 * @param ownerID
	 *            ID of the owner
	 * @param producers
	 *            array of all CFirms
	 */
	public Capital(int quantity, int ownerID, CFirm[] producers) {
		super(0);
		this.quantity = quantity;
		machines = new LinkedHashSet<Machine>();
		for (int i = 0; i < quantity; i++) {
			Machine machine = new Machine();
			machine.price = CFirm.INIT_CAPITAL_PRICE;
			machine.life = CFirm.CAPITAL_LIFE;
			machine.remainingLife = StdRandom.uniform(machine.life / 2)
					+ machine.life / 2;
			machine.producer = producers[StdRandom.uniform(producers.length)];
			machines.add(machine);
		}
		scrappedMachines = new ArrayList<Machine>();
		this.ownerID = ownerID;
	}

	/**
	 * Override, not implemented
	 * 
	 * @param amount
	 */
	public void increase(int amount) {

	}

	/**
	 * Override, not implemented
	 * 
	 * @param amount
	 * @return 0
	 */
	public int decrease(int amount) {
		return 0;
	}

	/**
	 * Add <tt>qty</tt> of machines produced by <tt>producer</tt>, with a
	 * per-step price of <tt>price</tt> and can be used for a maximum of
	 * <tt>life</tt> number of steps
	 * 
	 * @param qty
	 *            quantity of machines to add
	 * @param price
	 *            per-step price of one machine
	 * @param life
	 *            max number of steps the machines could be used for
	 * @param producer
	 *            CFirm that produces these machines
	 */
	public void add(int qty, double price, int life, CFirm producer) {
		this.quantity += qty;
		for (int i = 0; i < qty; i++) {
			Machine machine = new Machine();
			machine.price = price;
			machine.life = life;
			machine.remainingLife = life;
			machine.producer = producer;
			machines.add(machine);
		}
	}

	/**
	 * Return the present value of all machines
	 * 
	 * @return the present value of all machines
	 */
	public double getPresentValue() {
		double val = 0;
		for (Machine machine : machines)
			val += machine.price * machine.remainingLife;
		return val;
	}

	/**
	 * Use the machines.
	 * 
	 * @return number of machines that can no longer be used
	 */
	public double useCapital() {
		int scrapped = 0;
		double cost = 0;
		for (Machine machine : machines) {
			machine.remainingLife--;
			cost += machine.price;
			Bank.pay(ownerID, machine.producer.getID(), machine.price,
					Bank.PRIIC);
			if (machine.remainingLife == 0) {
				scrappedMachines.add(machine);
				scrapped++;
				quantity--;
			}
		}

		for (Machine machine : scrappedMachines)
			machines.remove(machine);

		scrappedMachines.clear();

		return cost;
	}
}
