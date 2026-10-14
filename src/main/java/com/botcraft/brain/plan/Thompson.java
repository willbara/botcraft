package com.botcraft.brain.plan;

import java.util.Random;

/**
 * Sampling for "try what has worked, but don't write something off after a couple of unlucky
 * tries" (Thompson sampling). A method that worked {@code ok} times out of {@code n} gets a
 * success rate drawn from Beta(ok+1, n-ok+1): with little experience the draw varies a lot, so
 * an unlucky method still gets picked now and then; with lots it settles on what's true. Pure Java.
 */
public final class Thompson {
	private Thompson() {
	}

	/** A draw from Beta(a, b), a and b > 0. */
	public static double beta(double a, double b, Random r) {
		double x = gamma(a, r);
		double y = gamma(b, r);
		return x + y <= 0 ? 0.5 : x / (x + y);
	}

	/** A draw from Gamma(shape, 1) (Marsaglia and Tsang). */
	static double gamma(double shape, Random r) {
		if (shape < 1.0) {
			// Boost: Gamma(a) = Gamma(a+1) * U^(1/a).
			return gamma(shape + 1.0, r) * Math.pow(r.nextDouble(), 1.0 / shape);
		}
		double d = shape - 1.0 / 3.0;
		double c = 1.0 / Math.sqrt(9.0 * d);
		while (true) {
			double x;
			double v;
			do {
				x = r.nextGaussian();
				v = 1.0 + c * x;
			} while (v <= 0);
			v = v * v * v;
			double u = r.nextDouble();
			if (u < 1.0 - 0.0331 * x * x * x * x || Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
				return d * v;
			}
		}
	}
}
