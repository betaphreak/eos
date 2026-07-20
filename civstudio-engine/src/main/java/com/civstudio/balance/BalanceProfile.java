package com.civstudio.balance;

import com.civstudio.agent.GranaryConfig;
import com.civstudio.agent.RetinueConfig;
import com.civstudio.agent.firm.BuilderConfig;
import com.civstudio.agent.firm.ChildrenFirmConfig;
import com.civstudio.agent.firm.FirmConfig;
import com.civstudio.agent.firm.ScienceConfig;
import com.civstudio.agent.firm.StrategicFirmConfig;
import com.civstudio.agent.laborer.LaborerConfig;
import com.civstudio.agent.noble.NobleConfig;
import com.civstudio.bank.BankConfig;
import com.civstudio.market.WeddingConfig;

import lombok.Builder;

/**
 * The <b>agent-behaviour tuning</b> a standard colony founds on, as one aggregate — the injection
 * seam {@code SimulationHarness} was missing. Today a scenario tunes an agent by reaching for that
 * agent's own {@code setXxxConfig} (or by passing a non-{@code DEFAULT} config to a create method)
 * one at a time; a {@code BalanceProfile} is the whole set applied in a single
 * {@link com.civstudio.simulation.SimulationHarness#setBalanceProfile call}, so a tuned run — or a
 * <em>content-authored</em> one (see {@code docs/studio-control-plane-plan.md} workstream A) — is one
 * value with one home.
 *
 * <h2>What is and isn't here</h2>
 * The eleven per-owner {@code *Config} records that founding actually reads. Deliberately excluded,
 * each with its own home:
 * <ul>
 * <li><b>The economy</b> ({@code Era.Economy} — prices, balances, tax rates, pool size) is authored
 *     content on the <em>era × race</em> axes ({@code EconomyCatalog}), not run tuning. A colony
 *     resolves it from the race of its province; a scenario overrides it via {@code
 *     SimulationHarness.tuneEconomy}. Keeping it out of the profile is the A1 decision — two authored
 *     matrices, one tuning profile.</li>
 * <li><b>Structural/run-level fields</b> ({@code numEFirms}, {@code foundAtCamp}, {@code durationYears}
 *     …) stay on {@link com.civstudio.simulation.SimulationConfig}, which has its own injection point
 *     (it is passed to {@code SimulationHarness.create}). The profile is <em>behaviour</em>, the
 *     config is <em>shape</em>.</li>
 * <li><b>{@code MarchConfig}</b> is caravan-march behaviour, not part of standard-colony founding, so
 *     it is applied where a caravan is built, not here. <b>{@code FertilityConfig}</b> rides {@code
 *     SimulationConfig} and is applied to the {@code Settlement}.</li>
 * </ul>
 *
 * <p>{@link #DEFAULT} composes each field's own {@code DEFAULT}, so applying it is behaviour-neutral
 * by construction — the ship criterion for A0.
 */
@Builder(toBuilder = true)
public record BalanceProfile(
		FirmConfig firm,
		BankConfig bank,
		NobleConfig noble,
		RetinueConfig retinue,
		LaborerConfig laborer,
		WeddingConfig wedding,
		GranaryConfig granary,
		ChildrenFirmConfig childrenFirm,
		StrategicFirmConfig strategicFirm,
		ScienceConfig science,
		BuilderConfig builderFirm) {

	/** The canonical profile: every agent on its own {@code DEFAULT}. Applying it changes nothing. */
	public static final BalanceProfile DEFAULT = new BalanceProfile(
			FirmConfig.DEFAULT,
			BankConfig.DEFAULT,
			NobleConfig.DEFAULT,
			RetinueConfig.DEFAULT,
			LaborerConfig.DEFAULT,
			WeddingConfig.DEFAULT,
			GranaryConfig.DEFAULT,
			ChildrenFirmConfig.DEFAULT,
			StrategicFirmConfig.DEFAULT,
			ScienceConfig.DEFAULT,
			BuilderConfig.DEFAULT);
}
