package com.yuutara.illustrationarchive;

import com.yuutara.illustrationarchive.desktop.DesktopClassDataTraining;
import com.yuutara.illustrationarchive.desktop.DesktopLauncher;
import com.yuutara.illustrationarchive.desktop.LegacyMigration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class IllustrationArchiveApplication {

	public static void main(String[] args) throws IOException {
		// Explicit, one-off copy of a MySQL archive into a new desktop library; see LegacyMigration.
		if (args.length > 0 && LegacyMigration.COMMAND.equals(args[0])) {
			System.exit(LegacyMigration.run(args, System.out));
		}
		// Run by build-desktop.ps1 while packaging; see DesktopClassDataTraining.
		if (args.length > 0 && DesktopClassDataTraining.COMMAND.equals(args[0])) {
			DesktopClassDataTraining.run();
			System.exit(0);
		}
		// The jpackage desktop app sets this flag; the normal MySQL server start is unchanged.
		if (Boolean.getBoolean("illustration-archive.desktop")) {
			DesktopLauncher.main(args);
			return;
		}
		SpringApplication.run(IllustrationArchiveApplication.class, args);
	}

}
