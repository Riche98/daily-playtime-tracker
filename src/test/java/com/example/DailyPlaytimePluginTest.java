package com.example;

   import net.runelite.client.RuneLite;
   import net.runelite.client.externalplugins.ExternalPluginManager;

   public class DailyPlaytimePluginTest
   {
       public static void main(String[] args) throws Exception
       {
           ExternalPluginManager.loadBuiltin(DailyPlaytimePlugin.class);
           RuneLite.main(args);
       }
   }