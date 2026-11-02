package com.yuutara.illustrationarchive.service;

/**
 * Proof that a saved credential really worked, as opposed to merely being saved. The desktop Settings page shows it;
 * a server deployment has nowhere to show it, so the defaults keep nothing.
 *
 * <p>Each proof names the credential it is about, taken before the work starts. A result that arrives after the
 * credential changed therefore proves something about the old one, and says nothing about the new one.
 */
public interface IntegrationEvidence {

	/** Which X login a sync starting now would use; empty when there is none to vouch for. */
	default String xAttempt() {
		return "";
	}

	/** @param account the name X returned for this login, or null when it gave none worth showing */
	default void xWorked(String attempt, String account) {
	}

	/** Which Gemini key an analysis starting now would use; empty when there is none to vouch for. */
	default String aiAttempt() {
		return "";
	}

	default void aiWorked(String attempt) {
	}
}
