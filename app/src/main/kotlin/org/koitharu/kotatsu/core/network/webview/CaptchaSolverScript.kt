package org.koitharu.kotatsu.core.network.webview

/**
 * JavaScript code injected into the WebView to automatically solve
 * CloudFlare JS challenges (Turnstile, Managed Challenge, and generic checkbox challenges).
 *
 * The script runs after page load and periodically retries until the challenge is solved
 * or the timeout is reached.
 */
internal object CaptchaSolverScript {

	/**
	 * The main auto-solve script. It:
	 * 1. Detects CloudFlare Turnstile iframes and clicks the checkbox inside
	 * 2. Detects CloudFlare Managed Challenge checkboxes and clicks them
	 * 3. Detects generic challenge form elements and interacts with them
	 * 4. Auto-submits forms if present
	 *
	 * Returns a string status for debugging purposes.
	 */
	val SOLVE_SCRIPT: String = """
		(function() {
			try {
				// Strategy 1: CloudFlare Turnstile iframe
				var turnstileIframes = document.querySelectorAll(
					'iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"], iframe[title*="Cloudflare"]'
				);
				for (var i = 0; i < turnstileIframes.length; i++) {
					try {
						var iframeDoc = turnstileIframes[i].contentDocument || turnstileIframes[i].contentWindow.document;
						if (iframeDoc) {
							// Click the Turnstile checkbox
							var checkbox = iframeDoc.querySelector(
								'input[type="checkbox"], .cb-lb, #challenge-stage input, [role="checkbox"]'
							);
							if (checkbox) {
								checkbox.click();
								return 'turnstile_checkbox_clicked';
							}
							// Click the Turnstile body (some versions respond to body clicks)
							var body = iframeDoc.querySelector('body');
							if (body) {
								body.click();
								return 'turnstile_body_clicked';
							}
						}
					} catch(e) {
						// Cross-origin iframe, cannot access contentDocument
						// Try clicking the iframe element itself
						turnstileIframes[i].click();
						return 'turnstile_iframe_clicked';
					}
				}

				// Strategy 2: CloudFlare Managed Challenge (non-iframe)
				var challengeCheckbox = document.querySelector(
					'#challenge-stage input[type="checkbox"], ' +
					'#challenge-stage .ctp-checkbox-label, ' +
					'.challenge-form input[type="checkbox"], ' +
					'#cf-challenge input[type="checkbox"]'
				);
				if (challengeCheckbox) {
					challengeCheckbox.click();
					return 'managed_challenge_clicked';
				}

				// Strategy 3: Click the challenge-stage div (some CF versions need this)
				var challengeStage = document.querySelector('#challenge-stage, .challenge-stage');
				if (challengeStage) {
					var clickable = challengeStage.querySelector(
						'input[type="submit"], input[type="button"], button, .btn, [role="button"]'
					);
					if (clickable) {
						clickable.click();
						return 'challenge_button_clicked';
					}
				}

				// Strategy 4: Generic captcha checkbox (reCAPTCHA-style)
				var recaptchaIframes = document.querySelectorAll(
					'iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[title*="reCAPTCHA"], iframe[title*="hCaptcha"]'
				);
				for (var i = 0; i < recaptchaIframes.length; i++) {
					try {
						var rcDoc = recaptchaIframes[i].contentDocument || recaptchaIframes[i].contentWindow.document;
						if (rcDoc) {
							var rcCheckbox = rcDoc.querySelector(
								'.recaptcha-checkbox-border, .recaptcha-checkbox, #recaptcha-anchor'
							);
							if (rcCheckbox) {
								rcCheckbox.click();
								return 'recaptcha_checkbox_clicked';
							}
						}
					} catch(e) {
						recaptchaIframes[i].click();
						return 'recaptcha_iframe_clicked';
					}
				}

				// Strategy 5: Auto-submit any challenge form
				var forms = document.querySelectorAll(
					'form#challenge-form, form.challenge-form, form[action*="challenge"]'
				);
				for (var i = 0; i < forms.length; i++) {
					var submitBtn = forms[i].querySelector(
						'input[type="submit"], button[type="submit"], button'
					);
					if (submitBtn) {
						submitBtn.click();
						return 'form_submitted';
					}
				}

				// Strategy 6: Click any visible verify/checkbox button on the page
				var verifyButtons = document.querySelectorAll(
					'input[type="button"][value*="Verify"], input[type="submit"][value*="Verify"], ' +
					'button:contains("Verify"), .verify-button, #verify-button'
				);
				for (var i = 0; i < verifyButtons.length; i++) {
					if (verifyButtons[i].offsetParent !== null) {
						verifyButtons[i].click();
						return 'verify_button_clicked';
					}
				}

				return 'no_challenge_found';
			} catch(e) {
				return 'error: ' + e.message;
			}
		})();
	""".trimIndent()

	/**
	 * A lightweight check to determine if the current page looks like a CloudFlare challenge page.
	 * Returns "true" or "false" as a string.
	 */
	val DETECT_CHALLENGE_SCRIPT: String = """
		(function() {
			var hasChallenge = !!(
				document.querySelector('#challenge-stage') ||
				document.querySelector('.challenge-stage') ||
				document.querySelector('#challenge-form') ||
				document.querySelector('#cf-challenge') ||
				document.querySelector('iframe[src*="challenges.cloudflare.com"]') ||
				document.querySelector('iframe[src*="turnstile"]') ||
				document.querySelector('#challenge-error-title') ||
				document.querySelector('#challenge-error-text') ||
				document.querySelector('.ctp-checkbox-label') ||
				document.title.toLowerCase().indexOf('just a moment') !== -1 ||
				document.title.toLowerCase().indexOf('attention required') !== -1 ||
				document.title.toLowerCase().indexOf('cloudflare') !== -1
			);
			return hasChallenge ? 'true' : 'false';
		})();
	""".trimIndent()
}
