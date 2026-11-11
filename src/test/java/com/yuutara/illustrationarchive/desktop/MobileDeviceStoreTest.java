package com.yuutara.illustrationarchive.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobileDeviceStoreTest {

	@Test
	void aPairedPhoneIsRememberedAcrossARestartWithoutWritingItsToken(@TempDir Path home) throws Exception {
		MobileDeviceStore first = new MobileDeviceStore(home.toString());
		MobileDeviceStore.Issued issued = first.add("微信 · Android");
		assertTrue(first.accepts(issued.token()));
		assertEquals("微信 · Android", issued.device().label());

		Path file = home.resolve("config").resolve("mobile-devices.properties");
		String saved = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(saved.contains("device." + MobileDeviceStore.hash(issued.token())), "the token hash is stored");
		assertFalse(saved.contains(issued.token()), "the raw token never reaches the file");

		MobileDeviceStore second = new MobileDeviceStore(home.toString());
		assertTrue(second.accepts(issued.token()), "a restart keeps the phone paired");
		assertEquals(1, second.devices().size());
		assertTrue(second.revoke(issued.device().id()));
		assertFalse(second.accepts(issued.token()), "revoking takes effect immediately");
		assertFalse(new MobileDeviceStore(home.toString()).accepts(issued.token()), "and stays revoked after a restart");
	}

	@Test
	void anUnknownTokenOrIdIsRefusedAndBlankLabelsGetAName(@TempDir Path home) {
		MobileDeviceStore store = new MobileDeviceStore(home.toString());
		assertFalse(store.accepts(null));
		assertFalse(store.accepts("not-a-token"));
		assertFalse(store.revoke("000000000000"));
		assertFalse(store.revoke("11"), "a short prefix must not revoke a batch of phones");
		assertEquals("手机", store.add(" \u0000 ").device().label());
		assertEquals(60, store.add("x".repeat(80)).device().label().length());
	}

	@Test
	void aMalformedFileIsIgnoredEntryByEntryInsteadOfLockingThePhoneOut(@TempDir Path home) throws Exception {
		Files.createDirectories(home.resolve("config"));
		Files.writeString(home.resolve("config").resolve("mobile-devices.properties"), """
				device.0000000000000000000000000000000000000000000000000000000000000000.label=broken date
				device.not-a-hash.label=bad hash
				device.1111111111111111111111111111111111111111111111111111111111111111.label=good
				device.1111111111111111111111111111111111111111111111111111111111111111.paired-at=2026-11-12T00:00:00Z
				""", StandardCharsets.UTF_8);
		MobileDeviceStore store = new MobileDeviceStore(home.toString());
		assertEquals(1, store.devices().size());
		assertEquals("good", store.devices().get(0).label());
	}
}
