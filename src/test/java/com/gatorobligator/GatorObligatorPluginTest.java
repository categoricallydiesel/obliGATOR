package com.gatorobligator;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GatorObligatorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GatorObligatorPlugin.class);
		RuneLite.main(args);
	}
}
