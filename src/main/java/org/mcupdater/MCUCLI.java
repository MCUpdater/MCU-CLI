package org.mcupdater;

import com.google.gson.Gson;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.BuiltinHelpFormatter;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.mcupdater.downloadlib.DownloadQueue;
import org.mcupdater.downloadlib.Downloadable;
import org.mcupdater.downloadlib.TrackerListener;
import org.mcupdater.instance.Instance;
import org.mcupdater.model.*;
import org.mcupdater.model.Module;
import org.mcupdater.mojang.MinecraftVersion;
import org.mcupdater.settings.Profile;
import org.mcupdater.util.MCUpdater;
import org.mcupdater.util.ServerPackParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class MCUCLI extends MCUApp implements TrackerListener {

	private final static Gson gson = new Gson();
	private static boolean DEBUG = false;
	private static MCUCLI instance;

	private Deque<DownloadQueue> queues = new ArrayDeque<>();


	public static void main(String args[]) {
		System.setProperty("java.net.preferIPv4Stack", "true");
		instance = new MCUCLI();
		OptionParser optParser = new OptionParser();
		optParser.accepts("help", "Show help").forHelp();
		optParser.formatHelpWith(new BuiltinHelpFormatter(160, 3));
		ArgumentAcceptingOptionSpec<URL> packSpec = optParser.accepts("pack", "Pack URL").withRequiredArg().ofType(URL.class).required();
		ArgumentAcceptingOptionSpec<String> serverSpec = optParser.accepts("server", "Server ID").withRequiredArg().ofType(String.class).required();
		ArgumentAcceptingOptionSpec<File> pathSpec = optParser.accepts("path", "Install Path").withRequiredArg().ofType(File.class).required();
		ArgumentAcceptingOptionSpec<ModSide> sideSpec = optParser.accepts("side", "Installation side").withRequiredArg().ofType(ModSide.class).defaultsTo(ModSide.SERVER);
		optParser.accepts("debug", "Enable debugging");
		optParser.accepts("clean", "Do clean install");
		final OptionSet options = optParser.parse(args);
		if (options.has("debug")) {
			DEBUG = true;
		}
		if (options.has("help")) {
			try {
				optParser.printHelpOn(System.out);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return;
		}
		URL pack = packSpec.value(options);
		String server = serverSpec.value(options);
		Path installPath = pathSpec.value(options).toPath();
		ModSide side = sideSpec.value(options);
		if (side.equals(ModSide.BOTH)) {
			instance.baseLogger.severe("Invalid side specified!");
			return;
		}
		MCUpdater.getInstance(installPath.toFile()).setParent(instance);
		instance.doUpdate(pack, server, installPath, side, options.has("clean"));
	}

	private void doUpdate(URL packURL, String server, Path installPath, ModSide side, boolean clean) {
		ServerList pack = ServerPackParser.loadFromURL(packURL.toString(), server);
		List<GenericModule> modList = new ArrayList<>();
		List<ConfigFile> configs = new ArrayList<>();
		Instance instData;
		final Path instanceFile = installPath.resolve("instance.json");
		try {
			BufferedReader reader = Files.newBufferedReader(instanceFile, StandardCharsets.UTF_8);
			instData = gson.fromJson(reader, Instance.class);
			reader.close();
		} catch (IOException ioe) {
			instData = new Instance();
		}
		Set<String> digests = new HashSet<>();
		List<Module> fullModList = new ArrayList<>();
		fullModList.addAll(pack.getModules().values());
		for (Module mod : fullModList) {
			if (!mod.getMD5().isEmpty()) {
				digests.add(mod.getMD5());
			}
			for (ConfigFile cf : mod.getConfigs()) {
				if (!cf.getMD5().isEmpty()) {
					digests.add(cf.getMD5());
				}
			}
			for (GenericModule sm : mod.getSubmodules()) {
				if (!sm.getMD5().isEmpty()) {
					digests.add(sm.getMD5());
				}
			}
		}
		instData.setHash(MCUpdater.calculateGroupHash(digests));
		if (instData.getOptionalMods() == null) {
			instData.setOptionalMods(new HashMap<String, Boolean>());
		}

		for (Map.Entry<String, Module> entry : pack.getModules().entrySet()) {
			if (entry.getValue().isSideValid(side)) {
				if (side.equals(ModSide.SERVER) || entry.getValue().getRequired() || (instData.getOptionalMods().containsKey(entry.getKey()) ? instData.getOptionalMods().get(entry.getKey()) : entry.getValue().getIsDefault())) {
					modList.add(entry.getValue());
					if (entry.getValue().hasSubmodules()) {
						for (GenericModule submod : entry.getValue().getSubmodules()) {
							if (submod.isSideValid(side)) {
								modList.add(submod);
							}
						}
					}
					if (entry.getValue().hasConfigs()) {
						configs.addAll(entry.getValue().getConfigs());
					}
				}
			}
		}
		try {
			MCUpdater.getInstance().installMods(pack, modList, configs, installPath, clean, instData, side);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}

	}

	private MCUCLI() {
		this.baseLogger = Logger.getLogger("MCUpdater");
		this.baseLogger.setLevel(Level.ALL);
		ConsoleHandler handler = new ConsoleHandler();
		handler.setFormatter(new SimpleFormatter());
		this.baseLogger.addHandler(handler);
	}

	@Override
	public void setStatus(String string) {
		log("Status: " + string);
	}

	@Override
	public void log(String msg) {
		baseLogger.info(msg);
	}

	@Override
	public Profile requestLogin(String username) {
		return null;
	}

	@Override
	public DownloadQueue submitNewQueue(String queueName, String parent,
	                                    Collection<Downloadable> files, File basePath, File cachePath) {
		DownloadQueue newQueue = new DownloadQueue(queueName, parent, this, files, basePath, cachePath);
		queues.add(newQueue);
		return newQueue;
	}

	@Override
	public DownloadQueue submitAssetsQueue(String queueName, String parent,
	                                       MinecraftVersion version) {
		return null;
	}

	@Override
	public void alert(String msg) {
		baseLogger.warning(msg);
	}

	@Override
	public void onQueueFinished(DownloadQueue queue) {
		baseLogger.info(queue.getName() + " has finished.");
		queues.remove(queue);
		if (queues.size() == 0) {
			baseLogger.info("All download queues have completed.");
		}
	}

	@Override
	public void onQueueProgress(DownloadQueue queue) {
		for (DownloadQueue active : queues) {
			System.out.print(active.getName() + ": " + active.getProgress() * 100.00F + " / ");
		}
		System.out.print("\r");
	}

	@Override
	public void printMessage(String msg) {

	}
}
