package com.gatorobligator;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "ObliGATOR",
	description = "Replaces the Obligator's spiked mace head with Zebak's head",
	tags = {"obligator", "zebak", "gator", "cosmetic"}
)
public class GatorObligatorPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GatorObligatorConfig config;

	@Inject
	private GatorThrows gatorThrows;

	@Inject
	private HeldGators heldGators;

	@Inject
	private GatorModel gatorModel;

	@Inject
	private ObligatorPreview preview;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Override
	protected void startUp() throws Exception
	{
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				heldGators.scanPlayers();
			}
		});
		log.debug("ObliGATOR started");
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientThread.invoke(() ->
		{
			gatorThrows.clear();
			heldGators.clear();
			gatorModel.reset();
			preview.disable();
		});
		log.debug("ObliGATOR stopped");
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		gatorThrows.onBeforeRender();
		heldGators.onBeforeRender();
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		gatorThrows.onGraphicChanged(event.getActor());
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		gatorThrows.onPlayerDespawned(event.getPlayer());
		heldGators.onPlayerDespawned(event.getPlayer());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			gatorThrows.clear();
			heldGators.clear();
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (developerMode && event.getCommand().equalsIgnoreCase("oblig"))
		{
			preview.handleCommand(event.getArguments());
		}
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		// the preview first, as it puts the Obligator back on the local player
		preview.onPlayerChanged(event.getPlayer());
		heldGators.onPlayerChanged(event.getPlayer());
	}

	@Provides
	GatorObligatorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GatorObligatorConfig.class);
	}
}
