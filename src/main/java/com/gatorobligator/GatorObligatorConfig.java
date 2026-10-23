package com.gatorobligator;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gator-obligator")
public interface GatorObligatorConfig extends Config
{
	@ConfigItem(
		keyName = "chomp",
		name = "Chomp on throw",
		description = "Play Zebak's bite animation when the Obligator is thrown with a 5-tick attack style"
	)
	default boolean chomp()
	{
		return true;
	}
}
