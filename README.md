# Donut Profit Tracker - Fabric, Minecraft 26.2, client-side

Requires: JDK 25, Gradle 9.5.1 (or the wrapper from the Fabric example mod), Loom 1.17.
Build: gradle build  ->  build/libs/donutprofit-1.0.0.jar
Install: drop the jar in .minecraft/mods with Fabric Loader 0.19.3+ and Fabric API for 26.2.

Use: /start begins, /stop prints total, hourly average and every transaction
(also saved to config/donutprofit-logs/).
Edit config/donutprofit.json so the gain/loss regexes match DonutSMP's real chat messages.
Passive only: reads incoming system messages, sends nothing to the server.
If Fabric API 0.156.0+26.2 does not resolve, use the latest 26.2 version from fabricmc.net/develop.
