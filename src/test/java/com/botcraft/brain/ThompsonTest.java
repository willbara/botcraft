package com.botcraft.brain;

import com.botcraft.brain.plan.Thompson;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThompsonTest {
	private static double mean(double a, double b, int n) {
		Random r = new Random(42);
		double sum = 0;
		for (int i = 0; i < n; i++) {
			double x = Thompson.beta(a, b, r);
			assertTrue(x >= 0 && x <= 1);
			sum += x;
		}
		return sum / n;
	}

	@Test
	void drawsHaveTheRightMean() {
		assertEquals(0.5, mean(1, 1, 20000), 0.02);
		assertEquals(0.8, mean(8, 2, 20000), 0.02);
		assertEquals(0.1, mean(0.5, 4.5, 20000), 0.02);
	}

	@Test
	void littleExperienceVariesMoreThanLots() {
		Random r = new Random(7);
		double lowMin = 1;
		double lowMax = 0;
		double highMin = 1;
		double highMax = 0;
		for (int i = 0; i < 2000; i++) {
			double low = Thompson.beta(2, 2, r);
			double high = Thompson.beta(200, 200, r);
			lowMin = Math.min(lowMin, low);
			lowMax = Math.max(lowMax, low);
			highMin = Math.min(highMin, high);
			highMax = Math.max(highMax, high);
		}
		assertTrue(lowMax - lowMin > 3 * (highMax - highMin));
	}
}
