package eos.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import eos.agent.firm.CFirm;
import eos.good.Capital;
import eos.util.StdRandom;

/**
 * A market trading capital good
 * 
 * @author zhihongx
 * 
 */
public class CapitalMarket extends Market {

	/* buy offer */
	private class BuyOffer {
		Capital capital;
		int qty;
	}

	/* sell offer */
	private class SellOffer {
		CFirm seller;
		double price;
		int capacity;
	}

	// buy offers
	private ArrayList<BuyOffer> buyOffers;

	// sell offers
	private LinkedHashSet<SellOffer> sellOffers;

	// volume of capital good traded
	private double mktGoodVol;

	// totalMetric supply of capital good
	private double supply;

	// sum of reciprocal of prices of all sell offers
	private double totalMetric;

	// sum of prices of all sell offers
	private double totalPrice;

	// average capital price
	private double avgPrice;

	/**
	 * Create a new capital market
	 */
	public CapitalMarket() {
		super("Capital");
		buyOffers = new ArrayList<BuyOffer>();
		sellOffers = new LinkedHashSet<SellOffer>();
		totalMetric = 0;
		totalPrice = 0;
		supply = 0;
	}

	/**
	 * Add a buy offer
	 * 
	 * @param capital
	 *            a reference to the capital stock owned by the buyer
	 *            <p>
	 * @param qty
	 *            quantity of machines to buy
	 */
	public void addBuyOffer(Capital capital, int qty) {
		BuyOffer offer = new BuyOffer();
		offer.qty = qty;
		offer.capital = capital;
		buyOffers.add(offer);
	}

	/**
	 * Add a sell offer
	 * 
	 * @param seller
	 *            <p>
	 * @param price
	 *            per-step price of each unit
	 *            <p>
	 * @param capacity
	 *            max quantity of capital available for sale
	 */
	public void addSellOffer(CFirm seller, double price, int capacity) {
		SellOffer offer = new SellOffer();
		offer.seller = seller;
		offer.capacity = capacity;
		offer.price = price;
		sellOffers.add(offer);
		totalMetric += 1 / price;
		totalPrice += price * capacity;
		supply += capacity;
	}

	/**
	 * Clear the market
	 */
	public void clear() {
		mktGoodVol = 0;
		Collections.shuffle(buyOffers, StdRandom.getRandom());
		for (BuyOffer buyOffer : buyOffers) {
			if (supply == 0)
				return;
			SellOffer picked = null;
			while (picked == null) {
				double winner = StdRandom.uniform();
				double val = 0;
				for (SellOffer sellOffer : sellOffers) {
					val += 1 / sellOffer.price / totalMetric;
					if (val > winner) {
						if (buyOffer.qty <= sellOffer.capacity) {
							picked = sellOffer;
							picked.capacity -= buyOffer.qty;
							mktGoodVol += buyOffer.qty;
						}
						break;
					}
				}
			}
			buyOffer.capital.add(buyOffer.qty, picked.price,
					CFirm.CAPITAL_LIFE, picked.seller);
			if (picked.capacity <= 0)
				sellOffers.remove(picked);
		}
		totalMetric = 0;
		avgPrice = totalPrice / supply;
		totalPrice = 0;
		supply = 0;
		buyOffers.clear();
		sellOffers.clear();
	}

	/**
	 * Return average price of sell offers posted
	 * 
	 * @return average price of sell offers posted
	 */
	public double getAvgPrice() {
		return avgPrice;
	}

	/**
	 * Return volume of capital good traded
	 * 
	 * @return volume of capital good traded
	 */
	public double getLastMktGoodVol() {
		return mktGoodVol;
	}

}
