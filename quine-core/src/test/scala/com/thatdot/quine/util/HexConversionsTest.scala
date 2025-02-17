package com.thatdot.quine.util

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import com.thatdot.common.util.ByteConversions

class ByteConversionsTests extends AnyFlatSpec with ScalaCheckDrivenPropertyChecks {

  // Array[Byte] => String => Array[Byte]
  "an array of bytes" should "roundtrip when converted to a string and back" in {
    forAll { (bytes: Array[Byte]) =>
      ByteConversions.parseHexBinary(ByteConversions.formatHexBinary(bytes)) sameElements bytes
    }
  }

  // String => Array[Byte] => String
  "a valid hex string" should "roundtrip when converted to an array of bytes and back" in {
    forAll(Gen.hexStr.filter(_.length % 2 == 0)) { (hex: String) =>
      ByteConversions.formatHexBinary(ByteConversions.parseHexBinary(hex)) == hex.toUpperCase
    }
  }
}
