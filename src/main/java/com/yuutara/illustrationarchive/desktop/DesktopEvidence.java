package com.yuutara.illustrationarchive.desktop;

import com.yuutara.illustrationarchive.service.GeminiKeyStore;
import com.yuutara.illustrationarchive.service.IntegrationEvidence;
import com.yuutara.illustrationarchive.service.XTokenManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * Keeps what the Settings page says has actually worked. Each proof names the login or key it is about, and is only
 * reported while that is still the one in use. That comparison, not deleting the record, is what stops a new
 * credential inheriting an old proof: the identity is saved together with the credential, so it holds even when
 * settings.properties cannot be written, and after a restart.
 *
 * <p>So the two ways a write can fail here are different. Failing to save a proof loses a nice-to-have from work that
 * already succeeded, and must not turn that work into a failure. Failing to delete an old proof changes nothing the
 * user can see, and only leaves stale text in the file until the next proof overwrites it.
 */
@Component
@Profile(DesktopServer.PROFILE)
public class DesktopEvidence implements IntegrationEvidence {

	private static final Logger log = LoggerFactory.getLogger(DesktopEvidence.class);

	private final DesktopSettings settings;
	private final XTokenManager tokens;
	private final GeminiKeyStore keys;

	public DesktopEvidence(DesktopSettings settings, XTokenManager tokens, GeminiKeyStore keys) {
		this.settings = settings;
		this.tokens = tokens;
		this.keys = keys;
	}

	@Override
	public String xAttempt() {
		return tokens.loginId();
	}

	@Override
	public synchronized void xWorked(String attempt, String account) {
		if (attempt.isEmpty() || !attempt.equals(tokens.loginId())) return;
		try {
			settings.saveXVerified(attempt, account, Instant.now());
		} catch (UncheckedIOException failed) {
			log.warn("A sync succeeded, but that could not be recorded. Settings will keep saying it is unverified.", failed);
		}
	}

	@Override
	public String aiAttempt() {
		return keys.fingerprint();
	}

	@Override
	public synchronized void aiWorked(String attempt) {
		if (attempt.isEmpty() || !attempt.equals(keys.fingerprint())) return;
		try {
			settings.saveAiVerified(attempt, Instant.now());
		} catch (UncheckedIOException failed) {
			log.warn("An analysis succeeded, but that could not be recorded. Settings will keep saying it is unverified.", failed);
		}
	}

	/** When a sync last finished on the current login; null when it has not, whatever the file still holds. */
	public synchronized String xVerifiedAt() {
		return xProofApplies() ? settings.xVerifiedAt() : null;
	}

	public synchronized String xVerifiedAccount() {
		return xProofApplies() ? settings.xVerifiedAccount() : null;
	}

	/** When an analysis last succeeded with the current key; null when it has not, whatever the file still holds. */
	public synchronized String aiVerifiedAt() {
		return aiProofApplies() ? settings.aiVerifiedAt() : null;
	}

	/** Housekeeping after the login changed. The old proof already stopped counting when the tokens were saved. */
	synchronized void loginChanged() {
		if (settings.xVerifiedAt() == null || xProofApplies()) return;
		try {
			settings.forgetXVerified();
		} catch (UncheckedIOException failed) {
			log.warn("Could not delete the proof of the previous X login. It no longer applies, so it will not be shown.", failed);
		}
	}

	/** Housekeeping after the key changed. The old proof already stopped counting when the key was saved. */
	synchronized void keyChanged() {
		if (settings.aiVerifiedAt() == null || aiProofApplies()) return;
		try {
			settings.forgetAiVerified();
		} catch (UncheckedIOException failed) {
			log.warn("Could not delete the proof of the previous Gemini key. It no longer applies, so it will not be shown.", failed);
		}
	}

	private boolean xProofApplies() {
		String login = tokens.loginId();
		return !login.isEmpty() && login.equals(settings.xVerifiedLogin());
	}

	private boolean aiProofApplies() {
		String key = keys.fingerprint();
		return !key.isEmpty() && key.equals(settings.aiVerifiedKey());
	}
}
