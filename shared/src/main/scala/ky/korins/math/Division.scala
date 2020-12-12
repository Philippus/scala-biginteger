/*
 * scala-biginteger - highly optimized BigInteger implementation for scala, scala-js and scala-native.
 *
 * Copyright 2020 Kirill A. Korinsky <kirill@korins.ky>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

/*
 * Ported by Alistair Johnson from
 * https://github.com/gwtproject/gwt/blob/master/user/super/com/google/gwt/emul/java/math/Division.java
 * Original license copied below:
 */

/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * INCLUDES MODIFICATIONS BY RICHARD ZSCHECH AS WELL AS GOOGLE.
 */

package ky.korins.math

import java.util.Arrays

import BigInteger.QuotAndRem
import ky.korins.math.Elementary.subtract

import scala.language.implicitConversions

/** Provides BigInteger division and modular arithmetic.
 *
 *  Object that provides all operations related with division and modular
 *  arithmetic to {@link BigInteger}. Some methods are provided in both mutable
 *  and immutable way. There are several variants provided listed below:
 *
 *  <ul type="circle"> <li><b>Division</b> <ul type="circle"> <li>
 *  {@link BigInteger} division and remainder by {@link BigInteger}.</li> <li>
 *  {@link BigInteger} division and remainder by {@code int}.</li> <li><i>gcd</i>
 *  between {@link BigInteger} numbers.</li> </ul> </li> <li><b>Modular
 *  arithmetic </b> <ul type="circle"> <li>Modular exponentiation between
 *  {@link BigInteger} numbers.</li> <li>Modular inverse of a {@link BigInteger}
 *  numbers.</li> </ul> </li> </ul>
 */
private[math] object Division {

  final val UINT_MAX = 0xffffffffL

  final val whenBurnikelZiegler = 80

  final val whenBurnikelZieglerDifferent = whenBurnikelZiegler / 2

  /** Divides an array by another array.
   *
   *  Divides the array 'a' by the array 'b' and gets the quotient and the
   *  remainder. Implements the Knuth's division algorithm. See D. Knuth, The Art
   *  of Computer Programming, vol. 2. Steps D1-D8 correspond the steps in the
   *  algorithm description.
   *
   *  @param quot the quotient
   *  @param quotLength the quotient's length
   *  @param a the dividend
   *  @param aLength the dividend's length
   *  @param b the divisor
   *  @param bLength the divisor's length
   *  @return the remainder
   */
  def divide(quot: Array[Int], quotLength: Int, a: Array[Int], aLength: Int,
      b: Array[Int], bLength: Int): Array[Int] = {
    val normA = new Array[Int](aLength + 1) // the normalized dividend an extra byte is needed for correct shift
    val normB = new Array[Int](bLength + 1) // the normalized divisor
    val normBLength = bLength
    /*
     * Step D1: normalize a and b and put the results to a1 and b1 the
     * normalized divisor's first digit must be >= 2^31
     */
    val divisorShift = java.lang.Integer.numberOfLeadingZeros(b(bLength - 1))
    if (divisorShift != 0) {
      BitLevel.shiftLeft(normB, b, 0, divisorShift)
      BitLevel.shiftLeft(normA, a, 0, divisorShift)
    } else {
      System.arraycopy(a, 0, normA, 0, aLength)
      System.arraycopy(b, 0, normB, 0, bLength)
    }
    val firstDivisorDigit = normB(normBLength - 1)
    // Step D2: set the quotient index
    var i = quotLength - 1
    var j = aLength

    while (i >= 0) {
      // Step D3: calculate a guess digit guessDigit
      var guessDigit = 0
      if (normA(j) == firstDivisorDigit) {
        // set guessDigit to the largest unsigned int value
        guessDigit = -1
      } else {
        val product: Long =
          ((normA(j) & UINT_MAX) << 32) + (normA(j - 1) & UINT_MAX)
        val firstDivisorDigitLong = firstDivisorDigit & UINT_MAX
        val quotient =
          java.lang.Long.divideUnsigned(product, firstDivisorDigitLong)
        guessDigit = quotient.toInt
        var rem = (product - quotient * firstDivisorDigitLong).toInt

        // decrease guessDigit by 1 while leftHand > rightHand
        if (guessDigit != 0) {
          guessDigit += 1 // to have the proper value in the loop below

          while ({
            var oneMoreTime = false

            guessDigit -= 1
            // leftHand always fits in an unsigned long
            val leftHand: Long =
              (guessDigit & UINT_MAX) * (normB(normBLength - 2) & UINT_MAX)

            // rightHand can overflow. In this case the loop condition will be
            // true in the next step of the loop
            val rightHand: Long = (rem.toLong << 32) + (normA(j - 2) & UINT_MAX)
            val longR: Long = (rem & UINT_MAX) + (firstDivisorDigit & UINT_MAX)
            // checks that longR does not fit in an unsigned int.
            // this ensures that rightHand will overflow unsigned long in the next step
            if ((longR >>> 32).toInt == 0) {
              rem = longR.toInt

              if ((leftHand ^ Long.MinValue) > (rightHand ^ Long.MinValue)) {
                oneMoreTime = true
              }
            }

            oneMoreTime
          }) ()
        }
      }

      // Step D4: multiply normB by guessDigit and subtract the production
      // from normA.
      if (guessDigit != 0) {
        val borrow = Division.multiplyAndSubtract(normA, j - normBLength, normB, normBLength, guessDigit)
        // Step D5: check the borrow
        if (borrow != 0) {
          // Step D6: compensating addition
          guessDigit -= 1
          var carry: Long = 0
          var k = 0
          while (k < normBLength) {
            carry += (normA(j - normBLength + k) & UINT_MAX) + (normB(k) & UINT_MAX)
            normA(j - normBLength + k) = carry.toInt
            carry >>>= 32
            k += 1
          }
        }
      }
      if (quot != null) {
        quot(i) = guessDigit
      }
      // Step D7
      j -= 1
      i -= 1
    }
    /*
     * Step D8: we got the remainder in normA. Denormalize it id needed
     */
    if (divisorShift != 0) {
      // reuse normB
      BitLevel.shiftRight(normB, normBLength, normA, 0, divisorShift)
      normB
    } else {
      System.arraycopy(normA, 0, normB, 0, bLength)
      normA
    }
  }

  /** Computes the quotient and the remainder after a division by an {@code Int}.
   *
   *  @return an array of the form {@code [quotient, remainder]}.
   */
  def divideAndRemainderByInteger(bi: BigInteger, divisor: Int,
      divisorSign: Int): QuotAndRem = {
    val valDigits = bi.digits
    val valLen = bi.numberLength
    val valSign = bi.sign
    if (valLen == 1) {
      val valDigit = valDigits(0)
      var quo = Integer.divideUnsigned(valDigit, divisor) & UINT_MAX
      var rem = Integer.remainderUnsigned(valDigit, divisor) & UINT_MAX
      if (valSign != divisorSign)
        quo = -quo
      if (valSign < 0)
        rem = -rem
      new QuotAndRem(BigInteger.valueOf(quo), BigInteger.valueOf(rem))
    } else {
      val quotientLength = valLen
      val quotientSign = if (valSign == divisorSign) 1 else -1
      val quotientDigits = new Array[Int](quotientLength)
      val div = divideArrayByInt(quotientDigits, valDigits, valLen, divisor)
      val remainderDigits = Array(div)
      val result0 = new BigInteger(quotientSign, quotientLength, quotientDigits)
      val result1 = new BigInteger(valSign, 1, remainderDigits)
      result0.cutOffLeadingZeroes()
      result1.cutOffLeadingZeroes()
      new QuotAndRem(result0, result1)
    }
  }

  /** Divides an array by an integer value.
   *
   *  Implements the Knuth's division algorithm.
   *  See D. Knuth, The Art of Computer Programming, vol. 2.
   *
   *  @param dest the quotient
   *  @param src the dividend
   *  @param srcLength the length of the dividend
   *  @param divisor the divisor
   *  @return remainder
   */
  def divideArrayByInt(dest: Array[Int], src: Array[Int], srcLength: Int,
      divisor: Int): Int = {
    var rem: Int = 0
    val bLong: Long = divisor & UINT_MAX
    var i = srcLength - 1
    while (i >= 0) {
      val temp: Long = (rem.toLong << 32) | (src(i) & UINT_MAX)
      val quot = java.lang.Long.divideUnsigned(temp, bLong)
      rem = (temp - quot * bLong).toInt
      dest(i) = quot.toInt
      i -= 1
    }
    rem
  }

  /** Performs modular exponentiation using the Montgomery Reduction.
   *
   *  It requires that all parameters be positive and the modulus be even.
   *  Based <i>The square and multiply algorithm and the Montgomery Reduction C. K. Koc -
   *  Montgomery Reduction with Even Modulus</i>. The square and multiply
   *  algorithm and the Montgomery Reduction.
   *
   *  @ar.org.fitc.ref "C. K. Koc - Montgomery Reduction with Even Modulus"
   *  @see BigInteger#modPow(BigInteger, BigInteger)
   */
  def evenModPow(base: BigInteger, exponent: BigInteger,
      modulus: BigInteger): BigInteger = {
    // STEP 1: Obtain the factorization 'modulus'= q * 2^j.
    val j = modulus.getLowestSetBit()
    val q = modulus.shiftRight(j)

    // STEP 2: Compute x1 := base^exponent (mod q).
    val x1 = oddModPow(base, exponent, q)

    // STEP 3: Compute x2 := base^exponent (mod 2^j).
    val x2 = pow2ModPow(base, exponent, j)

    // STEP 4: Compute q^(-1) (mod 2^j) and y := (x2-x1) * q^(-1) (mod 2^j)
    val qInv = modPow2Inverse(q, j)
    var y = x2.subtract(x1).multiply(qInv)
    inplaceModPow2(y, j)
    if (y.sign < 0)
      y = y.add(BigInteger.getPowerOfTwo(j))

    // STEP 5: Compute and return: x1 + q * y
    x1.add(q.multiply(y))
  }

  /** Performs the final reduction of the Montgomery algorithm.
   *
   *  @see #monPro(BigInteger, BigInteger, BigInteger, long)
   *  @see #monSquare(BigInteger, BigInteger, long)
   */
  def finalSubtraction(res: Array[Int], modulus: BigInteger): Unit  = {
    // skipping leading zeros
    val modulusLen = modulus.numberLength
    val modulusDigits = modulus.digits
    var doSub = res(modulusLen) != 0
    if (!doSub) {
      doSub = true
      var i = modulusLen - 1
      while (i >= 0) {
        if (res(i) != modulusDigits(i)) {
          doSub =
            (res(i) != 0) && ((res(i) & UINT_MAX) > (modulusDigits(i) & UINT_MAX))
          //force break
          i = 0
        }
        i -= 1
      }
    }

    if (doSub)
      subtract(res, res, modulusLen + 1, modulusDigits, modulusLen)
  }

  /** Return the greatest common divisor of two BigIntegers
   *
   *  @param val1 must be greater than zero
   *  @param val2 must be greater than zero
   *  @see BigInteger#gcd(BigInteger)
   *  @return {@code GCD(val1, val2)}
   */
  def gcdBinary(val1: BigInteger, val2: BigInteger): BigInteger = {
    var op1 = val1
    var op2 = val2

    /*
     * Divide both number the maximal possible times by 2 without rounding
     * gcd(2*a, 2*b) = 2 * gcd(a,b)
     */
    val lsb1 = op1.getLowestSetBit()
    val lsb2 = op2.getLowestSetBit()
    val pow2Count = Math.min(lsb1, lsb2)
    BitLevel.inplaceShiftRight(op1, lsb1)
    BitLevel.inplaceShiftRight(op2, lsb2)

    // I want op2 > op1
    if (op1.compareTo(op2) == BigInteger.GREATER) {
      val swap: BigInteger = op1
      op1 = op2
      op2 = swap
    }

    while ({
      var oneMoreTime = false

      // INV: op2 >= op1 && both are odd unless op1 = 0

      // Optimization for small operands
      // (op2.bitLength() < 32) implies by INV (op1.bitLength() < 32)
      if ((op2.numberLength == 1) && (op2.digits(0) > 0)) {
        op2 = BigInteger.valueOf(Division.gcdBinary(op1.intValue(), op2.intValue()))
      } else {
        // Implements one step of the Euclidean algorithm
        // To reduce one operand if it's much smaller than the other one
        if (op2.numberLength > op1.numberLength * 1.2) {
          op2 = op2.remainder(op1)
          if (op2.signum() != 0) {
            BitLevel.inplaceShiftRight(op2, op2.getLowestSetBit())
          }
        } else {
          // Use Knuth's algorithm of successive subtract and shifting
          while ({
            Elementary.inplaceSubtract(op2, op1)
            BitLevel.inplaceShiftRight(op2, op2.getLowestSetBit())

            op2.compareTo(op1) >= BigInteger.EQUALS
          }) ()
        }
        // now op1 >= op2
        val swap: BigInteger = op2
        op2 = op1
        op1 = swap
        if (op1.sign != 0)
          oneMoreTime = true
      }

      oneMoreTime
    }) ()
    op2.shiftLeft(pow2Count)
  }

  /** Return the greatest common divisor of two, positive BigIntegers.
   *
   *  Performs the same as {@link #gcdBinary(BigInteger, BigInteger)}, but with
   *  numbers of 31 bits, represented in positives values of {@code Int} type.
   *
   *  @param val1 a positive number
   *  @param val2 a positive number
   *  @see #gcdBinary(BigInteger, BigInteger)
   *  @return <code>GCD(val1, val2)</code>
   */
  def gcdBinary(val1: Int, val2: Int): Int = {
    var op1 = val1
    var op2 = val2

    val lsb1 = java.lang.Integer.numberOfTrailingZeros(op1)
    val lsb2 = java.lang.Integer.numberOfTrailingZeros(op2)
    val pow2Count = Math.min(lsb1, lsb2)
    if (lsb1 != 0)
      op1 >>>= lsb1
    if (lsb2 != 0)
      op2 >>>= lsb2

    while ({
      if (op1 >= op2) {
        op1 -= op2
        op1 >>>= java.lang.Integer.numberOfTrailingZeros(op1)
      } else {
        op2 -= op1
        op2 >>>= java.lang.Integer.numberOfTrailingZeros(op2)
      }

      op1 != 0
    }) ()
    op2 << pow2Count
  }

  /** Performs {@code x = x mod (2<sup>n</sup>)}.
   *
   *  @param x a positive number, it will store the result.
   *  @param n a positive exponent of {@code 2}.
   */
  def inplaceModPow2(x: BigInteger, n: Int): Unit = {
    val fd = n >> 5
    var leadingZeros: Int = 0
    if (!(x.numberLength < fd || x.bitLength() <= n)) {
      leadingZeros = 32 - (n & 31)
      x.numberLength = fd + 1
      val shift =
        if (leadingZeros < 32) -1 >>> leadingZeros
        else 0
      x.digits(fd) &= shift
      x.cutOffLeadingZeroes()
    }
  }

  /** Calculates a modInverse based on the Lórencz algorithm.
   *
   *  Based on "New Algorithm for Classical Modular Inverse" Róbert Lórencz. LNCS
   *  2523 (2002)
   *
   *  @return a^(-1) mod m
   */
  def modInverseLorencz(a: BigInteger, modulo: BigInteger): BigInteger = {
    val max = Math.max(a.numberLength, modulo.numberLength)
    val uDigits = new Array[Int](max + 1) // enough place to make all the inplace operation
    val vDigits = new Array[Int](max + 1)
    System.arraycopy(modulo.digits, 0, uDigits, 0, modulo.numberLength)
    System.arraycopy(a.digits, 0, vDigits, 0, a.numberLength)
    var u = new BigInteger(modulo.sign, modulo.numberLength, uDigits)
    val v = new BigInteger(a.sign, a.numberLength, vDigits)
    var r = new BigInteger(0, 1, new Array[Int](max + 1))
    val s = new BigInteger(1, 1, new Array[Int](max + 1))

    s.digits(0) = 1
    var coefU = 0
    var coefV = 0
    val n = modulo.bitLength()
    var k: Int = 0

    while (!isPowerOfTwo(u, coefU) && !isPowerOfTwo(v, coefV)) {
      // modification of original algorithm: I calculate how many times the
      // algorithm will enter in the same branch of if
      k = howManyIterations(u, n)
      if (k != 0) {
        BitLevel.inplaceShiftLeft(u, k)
        if (coefU >= coefV) {
          BitLevel.inplaceShiftLeft(r, k)
        } else {
          BitLevel.inplaceShiftRight(s, Math.min(coefV - coefU, k))
          if (k - (coefV - coefU) > 0)
            BitLevel.inplaceShiftLeft(r, k - coefV + coefU)
        }
        coefU += k
      }
      k = howManyIterations(v, n)
      if (k != 0) {
        BitLevel.inplaceShiftLeft(v, k)
        if (coefV >= coefU) {
          BitLevel.inplaceShiftLeft(s, k)
        } else {
          BitLevel.inplaceShiftRight(r, Math.min(coefU - coefV, k))
          if (k - (coefU - coefV) > 0)
            BitLevel.inplaceShiftLeft(s, k - coefU + coefV)
        }
        coefV += k
      }

      if (u.signum() == v.signum()) {
        if (coefU <= coefV) {
          Elementary.completeInPlaceSubtract(u, v)
          Elementary.completeInPlaceSubtract(r, s)
        } else {
          Elementary.completeInPlaceSubtract(v, u)
          Elementary.completeInPlaceSubtract(s, r)
        }
      } else {
        if (coefU <= coefV) {
          Elementary.completeInPlaceAdd(u, v)
          Elementary.completeInPlaceAdd(r, s)
        } else {
          Elementary.completeInPlaceAdd(v, u)
          Elementary.completeInPlaceAdd(s, r)
        }
      }
      if (v.signum() == 0 || u.signum() == 0)
        throw new ArithmeticException("BigInteger not invertible.")
    }

    if (isPowerOfTwo(v, coefV)) {
      r = s
      if (v.signum() != u.signum())
        u = u.negate()
    }
    if (u.testBit(n))
      r = if (r.signum() < 0) r.negate() else modulo.subtract(r)
    if (r.signum() < 0)
      r = r.add(modulo)
    r
  }

  /** Calculates modInverse based on Savas algorithm
   *
   *  Calculates a.modInverse(p) Based on: Savas, E; Koc, C "The Montgomery
   *  Modular Inverse - Revised".
   */
  def modInverseMontgomery(a: BigInteger, p: BigInteger): BigInteger = {
    if (a.sign == 0) // ZERO hasn't inverse
      throw new ArithmeticException("BigInteger not invertible.")

    if (!p.testBit(0)) // montgomery inverse require even modulo
      return modInverseLorencz(a, p) // scalastyle:ignore

    val m = p.numberLength * 32
    val u: BigInteger = p.copy()
    val v: BigInteger = a.copy()
    val max = Math.max(v.numberLength, u.numberLength)

    val r: BigInteger = new BigInteger(1, 1, new Array[Int](max + 1))
    val s: BigInteger = new BigInteger(1, 1, new Array[Int](max + 1))
    s.digits(0) = 1

    var k = 0
    val lsbu = u.getLowestSetBit()
    val lsbv = v.getLowestSetBit()
    if (lsbu > lsbv) {
      BitLevel.inplaceShiftRight(u, lsbu)
      BitLevel.inplaceShiftRight(v, lsbv)
      BitLevel.inplaceShiftLeft(r, lsbv)
      k += lsbu - lsbv
    } else {
      BitLevel.inplaceShiftRight(u, lsbu)
      BitLevel.inplaceShiftRight(v, lsbv)
      BitLevel.inplaceShiftLeft(s, lsbu)
      k += lsbv - lsbu
    }

    r.sign = 1
    while (v.signum() > 0) {
      while (u.compareTo(v) > BigInteger.EQUALS) {
        Elementary.inplaceSubtract(u, v)
        val toShift = u.getLowestSetBit()
        BitLevel.inplaceShiftRight(u, toShift)
        Elementary.inplaceAdd(r, s)
        BitLevel.inplaceShiftLeft(s, toShift)
        k += toShift
      }

      while ({
        var oneMoreTime = false

        if (u.compareTo(v) <= BigInteger.EQUALS) {
          Elementary.inplaceSubtract(v, u)
          if (v.signum() != 0) {
            val toShift = v.getLowestSetBit()
            BitLevel.inplaceShiftRight(v, toShift)
            Elementary.inplaceAdd(s, r)
            BitLevel.inplaceShiftLeft(r, toShift)
            k += toShift
            oneMoreTime = true
          }
        }

        oneMoreTime
      }) ()
    }

    if (!u.isOne) // u is the gcd
      throw new ArithmeticException("BigInteger not invertible.")
    if (r.compareTo(p) >= BigInteger.EQUALS)
      Elementary.inplaceSubtract(r, p)

    val n1 = calcN(p)
    if (k > m) {
      val r2 = monPro(p.subtract(r), BigInteger.ONE, p, n1)
      monPro(r2, BigInteger.getPowerOfTwo(2*m - k), p, n1)
    } else {
      monPro(p.subtract(r), BigInteger.getPowerOfTwo(m - k), p, n1)
    }
  }

  /** Calculates a modInverse raised to the power of two.
   *
   *  @param x an odd positive number.
   *  @param n the exponent by which 2 is raised.
   *  @return {@code x<sup>-1</sup> (mod 2<sup>n</sup>)}.
   */
  def modPow2Inverse(x: BigInteger, n: Int): BigInteger = {
    val y = new BigInteger(1, new Array[Int](1 << n))
    y.numberLength = 1
    y.digits(0) = 1
    y.sign = 1
    var i = 1
    while (i < n) {
      if (BitLevel.testBit(x.multiply(y), i)) {
        y.digits(i >> 5) |= (1 << (i & 31))
      }
      i += 1
    }
    y
  }

  def monPro(a: BigInteger, b: BigInteger, modulus: BigInteger,
    n2: Int): BigInteger = {
    val modulusLen = modulus.numberLength
    val res = new Array[Int]((modulusLen << 1) + 1)

    monPro(res, a.digits, b.digits, b.numberLength, modulus, n2)

    val result = new BigInteger(1, modulusLen + 1, res)
    result.cutOffLeadingZeroes()
    result
  }

  private def numberLength(digits: Array[Int]): Int = {
    var numberLength = digits.length - 1
    while (numberLength >= 0 && digits(numberLength) == 0) {
      numberLength -= 1
    }
    numberLength + 1
  }

  /**
   * This is a mix of multiplication and montgomery reduction function
   */
  def monPro(res: Array[Int], aDigits: Array[Int], bDigits: Array[Int], bNumberLength: Int,
    modulus: BigInteger, n2: Int): Array[Int] = {

    val modulusDigits = modulus.digits
    val modulusLen = modulus.numberLength

    Arrays.fill(res, 0)
    val aLen = Math.min(modulusLen, numberLength(aDigits))
    val bLen = Math.min(modulusLen, bNumberLength)

    var carry: Long = 0 // unsigned
    val n2u = n2 & UINT_MAX

    var j = 0
    var i = 0
    while (i < aLen) {
      var multiplyCarry: Long = res(i) & UINT_MAX
      val aI = aDigits(i) & UINT_MAX
      multiplyCarry += aI * (bDigits(0) & UINT_MAX)

      var modulusCarry = multiplyCarry & UINT_MAX
      val n0 = ((modulusCarry & UINT_MAX) * n2u) & UINT_MAX

      modulusCarry += n0 * (modulusDigits(0) & UINT_MAX)
      res(i) = modulusCarry.toInt

      modulusCarry >>>= 32
      multiplyCarry >>>= 32

      j = 1
      while (j < bLen) {
        val idx = i + j
        multiplyCarry += aI * (bDigits(j) & UINT_MAX)
        multiplyCarry += (res(idx) & UINT_MAX)
        modulusCarry += multiplyCarry & UINT_MAX
        multiplyCarry >>>= 32

        modulusCarry += n0 * (modulusDigits(j) & UINT_MAX)
        res(idx) = modulusCarry.toInt
        modulusCarry >>>= 32

        j += 1
      }
      // j is bLen here
      while (j < modulusLen) {
        val idx = i + j
        multiplyCarry += (res(idx) & UINT_MAX)
        modulusCarry += multiplyCarry & UINT_MAX
        multiplyCarry >>>= 32

        modulusCarry += n0 * (modulusDigits(j) & UINT_MAX)
        res(idx) = modulusCarry.toInt
        modulusCarry >>>= 32

        j += 1
      }

      carry += multiplyCarry + modulusCarry
      res(i + modulusLen) = carry.toInt
      carry >>>= 32

      i += 1
    }
    // i is aLen here
    while (i < modulusLen) {
      var multiplyCarry: Long = res(i) & UINT_MAX

      var modulusCarry = multiplyCarry & UINT_MAX
      val n0 = ((modulusCarry & UINT_MAX) * n2u) & UINT_MAX

      modulusCarry += n0 * (modulusDigits(0) & UINT_MAX)
      res(i) = modulusCarry.toInt

      modulusCarry >>>= 32
      multiplyCarry >>>= 32

      j = 1
      while (j < modulusLen) {
        val idx = i + j
        multiplyCarry += (res(idx) & UINT_MAX)
        modulusCarry += multiplyCarry & UINT_MAX
        multiplyCarry >>>= 32

        modulusCarry += n0 * (modulusDigits(j) & UINT_MAX)
        res(idx) = modulusCarry.toInt
        modulusCarry >>>= 32

        j += 1
      }

      carry += multiplyCarry + modulusCarry
      res(i + modulusLen) = carry.toInt
      carry >>>= 32

      i += 1
    }

    System.arraycopy(res, modulusLen, res, 0, modulusLen)
    res(modulusLen) = carry.toInt

    finalSubtraction(res, modulus)
    res
  }

  def monSquare(a: BigInteger, modulus: BigInteger, n2: Int): BigInteger = {
    val modulusLen = modulus.numberLength
    val res = new Array[Int]((modulusLen << 1) + 1)

    monSquare(res, a.digits, modulus, n2)

    val result = new BigInteger(1, modulusLen + 1, res)
    result.cutOffLeadingZeroes()
    result
  }

  /**
   * This is a mix of square multiplication with bitshift and montgomery reduction function
   */
  def monSquare(res: Array[Int], aDigits: Array[Int], modulus: BigInteger, n2: Int): Array[Int] = {
    val modulusDigits = modulus.digits
    val modulusLen = modulus.numberLength

    Arrays.fill(res, 0)
    val aLen = Math.min(modulusLen, numberLength(aDigits))

    var shiftCarry: Long = 0
    var lastLeftBit = 0

    var i = 0
    var idx = 0
    while (i < aLen) {
      var multiplyCarry: Long = 0
      val aI = aDigits(i) & UINT_MAX
      var j = i + 1
      while (j < aLen) {
        val idx = i + j
        val t = aI * (aDigits(j) & UINT_MAX) + (res(idx) & UINT_MAX) + multiplyCarry
        res(idx) = t.toInt
        multiplyCarry = t >>> 32
        j += 1
      }
      res(i + aLen) = multiplyCarry.toInt

      var iVal = res(idx)
      var shifted = (iVal << 1) | lastLeftBit
      val t = aI * aI + (shifted & UINT_MAX) + shiftCarry
      res(idx) = t.toInt
      lastLeftBit = iVal >>> 31
      idx += 1

      iVal = res(idx)
      shifted = (iVal << 1) | lastLeftBit
      val t2 = (t >>> 32) + (shifted & UINT_MAX)
      res(idx) = t2.toInt
      shiftCarry = t2 >>> 32
      lastLeftBit = iVal >>> 31
      idx += 1

      i += 1
    }

    if (lastLeftBit != 0) {
      res(aLen << 1) = lastLeftBit
    }

    var outerCarry: Long = 0 // unsigned
    val n2u = n2 & UINT_MAX
    i = 0
    while (i < modulusLen) {
      var innerCarry: Long = 0 // unsigned
      val m = ((res(i) & UINT_MAX) * n2u) & UINT_MAX
      var j = 0
      while (j < modulusLen) {
        val idx = i + j
        innerCarry = m * (modulusDigits(j) & UINT_MAX) + (res(idx) & UINT_MAX) + innerCarry
        res(idx) = innerCarry.toInt
        innerCarry >>>= 32
        j += 1
      }
      val idx = i + modulusLen
      outerCarry = outerCarry + (res(idx) & UINT_MAX) + innerCarry
      res(idx) = outerCarry.toInt
      outerCarry >>>= 32
      i += 1
    }

    System.arraycopy(res, modulusLen, res, 0, modulusLen)
    res(modulusLen) = (shiftCarry + outerCarry).toInt

    finalSubtraction(res, modulus)
    res
  }

  /** Multiplies an array and subtracts it from a subarray of another array.
   *
   *  @param a the array to subtract from
   *  @param start the start element of the subarray of a
   *  @param b the array to be multiplied and subtracted
   *  @param bLen the length of b
   *  @param c the multiplier of b
   *  @return the carry element of subtraction
   */
  def multiplyAndSubtract(a: Array[Int], start: Int, b: Array[Int],
      bLen: Int, c: Int): Int = {
    var carry0: Int = 0 // unsigned
    var carry1: Int = 0 // signed
    var i = 0
    val cu = c & UINT_MAX
    while (i < bLen) {
      val nextCarry0 = (b(i) & UINT_MAX) * cu + (carry0 & UINT_MAX)
      val nextCarry1 =
        (a(start + i) & UINT_MAX) - (nextCarry0 & UINT_MAX) + carry1.toLong
      a(start + i) = nextCarry1.toInt
      carry1 = (nextCarry1 >> 32).toInt
      carry0 = (nextCarry0 >> 32).toInt
      i += 1
    }

    val finalCarry1 =
      (a(start + bLen) & UINT_MAX) - (carry0 & UINT_MAX) + carry1.toLong
    a(start + bLen) = finalCarry1.toInt
    (finalCarry1 >> 32).toInt
  }

  /** Performs modular exponentiation using the Montgomery Reduction.
   *
   *  It requires that all parameters be positive and the modulus be odd.
   *
   *  @see BigInteger#modPow(BigInteger, BigInteger)
   *  @see #monPro(BigInteger, BigInteger, BigInteger, int)
   *  @see #slidingWindow(BigInteger, BigInteger, BigInteger, BigInteger, int)
   *  @see #squareAndMultiply(BigInteger, BigInteger, BigInteger, BigInteger,
   *       int)
   */
  def oddModPow(base: BigInteger, exponent: BigInteger,
      modulus: BigInteger): BigInteger = {
    val k = modulus.numberLength << 5
    // n-residue of base [base * r (mod modulus)]
    val a2 = base.shiftLeft(k).mod(modulus)
    // n-residue of base [1 * r (mod modulus)]
    val x2 = BigInteger.getPowerOfTwo(k).mod(modulus)

    // Compute (modulus[0]^(-1)) (mod 2^32) for odd modulus
    val n2 = calcN(modulus)
    val res =
      if (modulus.numberLength == 1) squareAndMultiply(x2, a2, exponent, modulus, n2)
      else slidingWindow(x2, a2, exponent, modulus, n2)
    monPro(res, BigInteger.ONE, modulus, n2)
  }

  /** Performs {@code base<sup>exponent</sup> mod (2<sup>j</sup>)}.
   *
   *  It requires that all parameters be positive.
   *
   *  @return {@code base<sup>exponent</sup> mod (2<sup>j</sup>)}.
   *  @see BigInteger#modPow(BigInteger, BigInteger)
   */
  def pow2ModPow(base: BigInteger, exponent: BigInteger, j: Int): BigInteger = {
    var res = BigInteger.ONE
    val e = exponent.copy()
    val baseMod2toN = base.copy()
    /*
     * If 'base' is odd then it's coprime with 2^j and phi(2^j) = 2^(j-1); so we
     * can reduce reduce the exponent (mod 2^(j-1)).
     */
    if (base.testBit(0))
      inplaceModPow2(e, j - 1)

    inplaceModPow2(baseMod2toN, j)
    var i = e.bitLength() - 1
    while (i >= 0) {
      val res2 = res.copy()
      inplaceModPow2(res2, j)
      res = res.multiply(res2)
      if (BitLevel.testBit(e, i)) {
        res = res.multiply(baseMod2toN)
        inplaceModPow2(res, j)
      }
      i -= 1
    }
    inplaceModPow2(res, j)
    res
  }

  /** Divides a <code>BigInteger</code> by a signed <code>Int</code>.
   *
   *  Returns the remainder.
   *
   *  @param dividend the BigInteger to be divided. Must be non-negative.
   *  @param divisor a signed int
   *  @return divide % divisor
   */
  def remainder(dividend: BigInteger, divisor: Int): Int =
    remainderArrayByInt(dividend.digits, dividend.numberLength, divisor)

  /** Divides an array by an integer value.
   *
   *  Implements the Knuth's division algorithm.
   *  See D. Knuth, The Art of Computer Programming, vol. 2.
   *
   *  @param src the dividend
   *  @param srcLength the length of the dividend
   *  @param divisor the divisor
   *  @return remainder
   */
  def remainderArrayByInt(src: Array[Int], srcLength: Int, divisor: Int): Int = {
    val longDivisor = divisor.toLong & UINT_MAX
    var result: Int = 0
    var i = srcLength - 1
    while (i >= 0) {
      val temp = (result.toLong << 32) | (src(i).toLong & UINT_MAX)
      result = java.lang.Long.remainderUnsigned(temp, longDivisor).toInt
      i -= 1
    }
    result
  }

  // based on Multi–Precision Math Section 7.3.1 Barrett Modular Exponentiation
  def slidingWindowSize(bits: Int): Int = {
    if (bits <= 7) 2
    else if (bits <= 36) 3
    else if (bits <= 140) 4
    else if (bits <= 450) 5
    else if (bits <= 1303) 6
    else if (bits <= 3529) 7
    else 8
  }

  /** The Montgomery modular exponentiation.
   *
   *  Implements the Montgomery modular exponentiation based in <i>The sliding
   *  windows algorithm and the MongomeryReduction</i>.
   *
   *  @ar.org.fitc.ref
   *  "A. Menezes,P. van Oorschot, S. Vanstone - Handbook of Applied Cryptography"
   *  ;
   *
   *  @see #oddModPow(BigInteger, BigInteger, BigInteger)
   */
  def slidingWindow(x2: BigInteger, a2: BigInteger, exponent: BigInteger,
      modulus: BigInteger, n2: Int): BigInteger = {

    val windowSize = slidingWindowSize(exponent.bitLength())
    val tableSize = 1 << windowSize

    val modulusLen = modulus.numberLength
    var res_current = new Array[Int]((modulusLen << 1) + 1)
    var res_last = new Array[Int]((modulusLen << 1) + 1)
    System.arraycopy(x2.digits, 0, res_last, 0, Math.min(modulusLen, x2.numberLength))

    // fill odd low pows of a2
    val pows = new Array[BigInteger](tableSize)
    var lowexp: Int = 0

    var acc3: Int = 0
    pows(0) = a2
    val x3 = monSquare(a2, modulus, n2)
    var i = 1
    while (i < tableSize) {
      pows(i) = monPro(pows(i - 1), x3, modulus, n2)
      i += 1
    }
    i = exponent.bitLength() - 1
    while (i >= 0) {
      if (BitLevel.testBit(exponent, i)) {
        lowexp = 1
        acc3 = i
        var j = Math.max(i - windowSize, 0)
        while (j <= (i - 1)) {
          if (BitLevel.testBit(exponent, j)) {
            if (j < acc3) {
              acc3 = j
              lowexp = (lowexp << (i - j)) ^ 1
            } else {
              lowexp = lowexp ^ (1 << (j - acc3))
            }
          }
          j += 1
        }
        j = acc3
        while (j <= i) {
          val res = monSquare(res_current, res_last, modulus, n2)
          res_current = res_last
          res_last = res
          j += 1
        }
        val pow = pows((lowexp - 1) >> 1)
        val res = monPro(res_current, res_last, pow.digits, pow.numberLength, modulus, n2)
        res_current = res_last
        res_last = res
        i = acc3
      } else {
        val res = monSquare(res_current, res_last, modulus, n2)
        res_current = res_last
        res_last = res
      }
      i -= 1
    }

    val result = new BigInteger(1, modulusLen + 1, res_last)
    result.cutOffLeadingZeroes()
    result
  }

  def squareAndMultiply(x2: BigInteger, a2: BigInteger, exponent: BigInteger,
      modulus: BigInteger, n2: Int): BigInteger = {
    val modulusLen = modulus.numberLength
    var res_current = new Array[Int]((modulusLen << 1) + 1)
    var res_last = new Array[Int]((modulusLen << 1) + 1)

    System.arraycopy(x2.digits, 0, res_last, 0, Math.min(modulusLen, x2.numberLength))
    var i = exponent.bitLength() - 1
    while (i >= 0) {
      val res = monSquare(res_current, res_last, modulus, n2)
      res_current = res_last
      res_last = res
      if (BitLevel.testBit(exponent, i)) {
        val res = monPro(res_current, res_last, a2.digits, a2.numberLength, modulus, n2)
        res_current = res_last
        res_last = res
      }
      i -= 1
    }

    val result = new BigInteger(1, modulusLen + 1, res_last)
    result.cutOffLeadingZeroes()
    result
  }

  /** Calculate the first digit of the inverse. */
  private def calcN(a: BigInteger): Int = {
    val m0: Long = a.digits(0) & UINT_MAX
    var n2: Long = 1L
    var powerOfTwo: Long = 2L
    while ({
      if (((m0 * n2) & powerOfTwo) != 0)
        n2 |= powerOfTwo
      powerOfTwo <<= 1

      powerOfTwo < 0x100000000L
    }) ()
    n2 = -n2
    (n2 & UINT_MAX).toInt
  }

  /** How many iteration of Lorencz's algorithm would perform the same operation.
   *
   *  @param bi
   *  @param n
   *  @return
   */
  private def howManyIterations(bi: BigInteger, n: Int): Int = {
    var i = n - 1
    if (bi.sign > 0) {
      while (!bi.testBit(i)) {
        i -= 1
      }
      n - 1 - i
    } else {
      while (bi.testBit(i)) {
        i -= 1
      }
      n - 1 - Math.max(i, bi.getLowestSetBit())
    }
  }


  /** Returns {@code bi == abs(2^exp)}. */
  private def isPowerOfTwo(bi: BigInteger, exp: Int): Boolean = {
    val cond1 = (exp >> 5) == (bi.numberLength - 1)
    val cond2 = bi.digits(bi.numberLength - 1) == (1 << (exp & 31))
    var result = cond1 && cond2

    if (result) {
      var i = 0
      while (result && (i < bi.numberLength - 1)) {
        result = bi.digits(i) == 0
        i += 1
      }
    }
    result
  }

  // algorithm 3 from pg. 9 of the Burnikel-Ziegler paper
  def divideAndRemainderBurnikelZieglerPositive(aSrc: BigInteger, bSrc: BigInteger): QuotAndRem = {
    val r: Int = aSrc.numberLength
    val s: Int = bSrc.numberLength

    if (r < s) {
      return new QuotAndRem(BigInteger.ZERO, aSrc)
    }

    val m: Int = 1 << (32 - Integer.numberOfLeadingZeros(s / whenBurnikelZiegler))

    val j: Int = (s + m - 1) / m
    val n: Int = j * m
    val n32: Long = n.toLong << 5
    val sigma: Int = math.max(0, n32 - bSrc.bitLength()).toInt
    val a = aSrc.shiftLeft(sigma)
    val b = bSrc.shiftLeft(sigma)

    var t: Int = ((a.bitLength() + n32) / n32).toInt
    if (t < 2) {
      t = 2
    }

    val a1 = a.getBlock(t - 1, t, n)
    val a2 = a.getBlock(t - 2, t, n)

    var z = a1.shiftLeft(n32.toInt) add a2 // Z[t-2]

    var quotient = BigInteger.ZERO
    var c: QuotAndRem = null
    var i = t - 2
    while (i > 0) {
      c = divide2n1n(z, b)
      z = a.getBlock(i - 1, t, n)
      z = z add c.rem.shiftLeft(n32.toInt)
      quotient = (quotient add c.quot).shiftLeft(n32.toInt)
      i -= 1
    }

    c = divide2n1n(z, b)
    quotient = quotient add c.quot

    val remainder = c.rem.shiftRight(sigma)
    new QuotAndRem(quotient, remainder)
  }

  // algorithm 1 from pg. 4 of the Burnikel-Ziegler paper
  def divide2n1n(a: BigInteger, b: BigInteger): QuotAndRem = {
    if (a.sign == 0) {
      return QuotAndRem.ZERO_ZERO
    }

    val n = b.numberLength
    val n16 = n << 4 // n / 2 in bits

    if (n % 2 != 0 || n < whenBurnikelZiegler) {
      return a.divideAndRemainderImpl(b)
    }

    val c1 = divide3n2n(a.shiftRight(n16), b)

    val a4 = a.getLower(n16)
    val r = c1.rem.shiftLeft(n16) add a4
    val c2 = divide3n2n(r, b)

    new QuotAndRem(c1.quot.shiftLeft(n16) add c2.quot, c2.rem)
  }

  // algorithm 2 from pg. 5 of the Burnikel-Ziegler paper
  def divide3n2n(a: BigInteger, b: BigInteger): QuotAndRem = {
    val n = b.numberLength / 2
    val n32 = n << 5

    val a1 = a.shiftRight(n32 << 1)
    val a2 = a.shiftRight(n32).getLower(n32)
    val a3 = a.getLower(n32)
    val a12 = a1.shiftLeft(n32) add a2

    val b1 = b.shiftRight(n32)
    val b2 = b.getLower(n32)

    var q: BigInteger = null
    var r1: BigInteger = null
    if (a1.compareTo(b1) < 0) {
      val qr = divide2n1n(a12, b1)
      q = qr.quot
      r1 = qr.rem
    } else {
      q = ones(n)
      r1 = a12 subtract b1.shiftLeft(n32) add b1
    }

    val d = q multiply b2
    var r = r1.shiftLeft(n32) add a3 subtract d //paper says a4

    while (r.sign < 0) {
      r = r add b
      q = q subtract BigInteger.ONE
    }

    new QuotAndRem(q, r)
  }

  private def ones(n: Int): BigInteger = {
    val mag: Array[Int] = new Array[Int](n)
    Arrays.fill(mag, -1);
    new BigInteger(1, n, mag);
  }
}
