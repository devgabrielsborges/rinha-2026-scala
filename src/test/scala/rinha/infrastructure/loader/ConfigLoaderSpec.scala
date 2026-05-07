package rinha.infrastructure.loader

import munit.FunSuite
import rinha.domain.MccRiskMap

import java.nio.file.Paths

class ConfigLoaderSpec extends FunSuite:

  private val resourceDir = Paths.get("src", "main", "resources")

  test("loadNormalization parses normalization.json correctly") {
    val norm = ConfigLoader.loadNormalization(resourceDir.resolve("normalization.json"))
    assertEquals(norm.maxAmount, 10000.0f)
    assertEquals(norm.maxInstallments, 12.0f)
    assertEquals(norm.amountVsAvgRatio, 10.0f)
    assertEquals(norm.maxMinutes, 1440.0f)
    assertEquals(norm.maxKm, 1000.0f)
    assertEquals(norm.maxTxCount24h, 20.0f)
    assertEquals(norm.maxMerchantAvgAmount, 10000.0f)
  }

  test("loadMccRisk parses mcc_risk.json correctly") {
    val risk = ConfigLoader.loadMccRisk(resourceDir.resolve("mcc_risk.json"))
    assertEqualsFloat(risk.riskFor("5411"), 0.15f, 0.001f)
    assertEqualsFloat(risk.riskFor("7995"), 0.85f, 0.001f)
  }

  test("loadMccRisk returns default risk for unknown MCC") {
    val risk = ConfigLoader.loadMccRisk(resourceDir.resolve("mcc_risk.json"))
    assertEquals(risk.riskFor("9999"), MccRiskMap.DefaultRisk)
  }

  test("loadFromEnv loads both configs using default paths") {
    val (norm, risk) = ConfigLoader.loadFromEnv()
    assert(norm.maxAmount > 0.0f)
    assertEqualsFloat(risk.riskFor("5812"), 0.30f, 0.001f)
  }
