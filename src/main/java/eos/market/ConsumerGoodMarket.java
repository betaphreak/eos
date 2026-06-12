package eos.market;

import java.util.ArrayList;

import eos.agent.Agent;
import eos.bank.Bank;
import eos.settlement.Settlement;
import lombok.extern.java.Log;

/**
 * A market trading consumer goods.
 *
 * @author zhihongx
 *
 */
@Log
public class ConsumerGoodMarket extends Market {
	/***************** constants ********************************/
	// percentage by which price could change in each step
	private static final double zeta = 0.1;

	// price is flagged once it exceeds this multiple of its initial level
	private static final int PRICE_SKYROCKET_FACTOR = 10;

	/**********************************************************/

	/* buy offer */
	private class BuyOffer {
		Agent buyer;
		Demand demand;
	}

	/* sell offer */
	private class SellOffer {
		Agent seller;
		double qty; // quantity of good available for sale
	}

	// initial min and max price
	private final double initLow, initHigh;

	// buy offers submitted in the current step
	private ArrayList<BuyOffer> buyOffers;

	// sell offers submitted in the current step
	private ArrayList<SellOffer> sellOffers;

	// last market price
	private double mktPrice;

	// volume of good traded
	private double mktGoodVol;

	// volume of money exchanged
	private double mktMoneyVol;

	// total supply of good
	private double mktSupply;

	// whether the price is currently flagged as skyrocketed
	private boolean priceSkyrocketed = false;

	/**
	 * Create a new consumer good market trading a good named good
	 * 
	 * @param good
	 *            name of the good traded in the market
	 *            <p>
	 * @param initLow
	 *            min initial price
	 *            <p>
	 * @param initHigh
	 *            max initial price
	 *            <p>
	 * @param colony
	 *            the colony this market belongs to
	 */
	public ConsumerGoodMarket(String good, double initLow, double initHigh,
			Settlement colony) {
		super(good, colony);
		buyOffers = new ArrayList<BuyOffer>();
		sellOffers = new ArrayList<SellOffer>();
		this.initLow = initLow;
		this.initHigh = initHigh;
	}

	/**
	 * Add a buy offer
	 * 
	 * @param buyer
	 *            <p>
	 * @param demand
	 */
	public void addBuyOffer(Agent buyer, Demand demand) {
		BuyOffer offer = new BuyOffer();
		offer.buyer = buyer;
		offer.demand = demand;
		buyOffers.add(offer);
	}

	/**
	 * Add a sell offer
	 * 
	 * @param seller
	 *            <p>
	 * @param qty
	 *            quantity of good available for sale
	 */
	public void addSellOffer(Agent seller, double qty) {
		SellOffer offer = new SellOffer();
		offer.seller = seller;
		offer.qty = qty;
		sellOffers.add(offer);
	}

	/**
	 * Return the total demand given price
	 * 
	 * @param price
	 *            <p>
	 * @return total demand given price
	 */
	private double getDemand(double price) {
		double demand = 0;
		for (BuyOffer offer : buyOffers)
			demand += offer.demand.getDemand(price);
		return demand;
	}

	/**
	 * Return the total supply
	 * 
	 * @return the total supply
	 */
	private double getSupply() {
		double supply = 0;
		for (SellOffer offer : sellOffers)
			supply += offer.qty;
		return supply;
	}

	/**
	 * Clear the market.
	 */
	public void clear() {
		double low, high, price;
		if (colony.getTimeStep() == 0) {
			low = initLow;
			high = initHigh;
		} else {
			low = mktPrice * (1 - zeta);
			high = mktPrice * (1 + zeta);
		}

		double supply = getSupply();
		double demand;
		
		// find market price
		int iters = 0;
		while (true) {
			price = (low + high) / 2;
			demand = getDemand(price);
			if (Math.abs(demand - supply) < 0.1 || Math.abs(high - low) < 0.01)
				break;
			if (demand > supply)
				low = price;
			else
				high = price;
			// safety net: a non-finite price (or pathological non-convergence)
			// would otherwise loop forever, since NaN/Infinity fail both exit
			// tests above. A finite search converges well within this cap.
			if (!Double.isFinite(price) || ++iters > 100) {
				log.warning(String.format(
						"%s price search did not converge (price=%g supply=%g demand=%g); aborting",
						good, price, supply, demand));
				break;
			}
		}

		double vol = Math.min(supply, demand);

		// carry out transactions
		if (vol > 0.1) {
			for (BuyOffer offer : buyOffers) {
				double qty = offer.demand.getDemand(price) / demand
						* vol;
				double payAmt = qty * price;
				offer.buyer.getBank().withdraw(offer.buyer.getID(), payAmt);
				offer.buyer.getGood(good).increase(qty);
			}
			for (SellOffer offer : sellOffers) {
				double qty = offer.qty / supply * vol;
				double payAmt = qty * price;
				offer.seller.getBank().credit(offer.seller.getID(), payAmt,
						Bank.PRIIC);
				offer.seller.getGood(good).decrease(qty);
			}
		}

		mktPrice = price;

		// flag when the price drifts past a high multiple of its initial level,
		// and again when it returns below; log once per crossing
		double skyrocketThreshold = PRICE_SKYROCKET_FACTOR * initHigh;
		if (!priceSkyrocketed && mktPrice > skyrocketThreshold) {
			priceSkyrocketed = true;
			log.warning(String.format("%s skyrocketed to %.2f (>%dx init)",
					good, mktPrice, PRICE_SKYROCKET_FACTOR));
		} else if (priceSkyrocketed && mktPrice <= skyrocketThreshold) {
			priceSkyrocketed = false;
			log.info(String.format("%s back below threshold (%.2f)", good,
					mktPrice));
		}

		mktGoodVol = vol;
		mktMoneyVol = price * mktGoodVol;
		mktSupply = supply;

		buyOffers.clear();
		sellOffers.clear();
	}

	/**
	 * Return market price
	 * 
	 * @return market price
	 */
	public double getLastMktPrice() {
		return mktPrice;
	}

	/**
	 * Return volume of good traded
	 * 
	 * @return volume of good traded
	 */
	public double getLastMktGoodVol() {
		return mktGoodVol;
	}

	/**
	 * Return volume of money exchanged
	 * 
	 * @return volume of money exchanged
	 */
	public double getLastMktMoneyVol() {
		return mktMoneyVol;
	}

	/**
	 * Return total supply
	 * 
	 * @return total supply
	 */
	public double getLastMktSupply() {
		return mktSupply;
	}

	@Override
	public String toString() {
		return String.format(
				"%s [price=%.3f vol=%.1f supply=%.1f buys=%d sells=%d]",
				name, mktPrice, mktGoodVol, mktSupply, buyOffers.size(),
				sellOffers.size());
	}
}
