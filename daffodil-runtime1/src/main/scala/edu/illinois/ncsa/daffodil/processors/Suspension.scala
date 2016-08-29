/* Copyright (c) 2016 Tresys Technology, LLC. All rights reserved.
 *
 * Developed by: Tresys Technology, LLC
 *               http://www.tresys.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal with
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimers.
 *
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimers in the
 *     documentation and/or other materials provided with the distribution.
 *
 *  3. Neither the names of Tresys Technology, nor the names of its contributors
 *     may be used to endorse or promote products derived from this Software
 *     without specific prior written permission.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
 * SOFTWARE.
 */

package edu.illinois.ncsa.daffodil.processors

import edu.illinois.ncsa.daffodil.processors.unparsers.UState
import edu.illinois.ncsa.daffodil.dpath.WhereBlockedLocation
import edu.illinois.ncsa.daffodil.util.Coroutine
import edu.illinois.ncsa.daffodil.exceptions.Assert
import edu.illinois.ncsa.daffodil.util.CoroutineException
import edu.illinois.ncsa.daffodil.util.MaybeULong
import edu.illinois.ncsa.daffodil.io.DirectOrBufferedDataOutputStream
import edu.illinois.ncsa.daffodil.util.Logging
import edu.illinois.ncsa.daffodil.util.LogLevel

/**
 * Performs transient things like evaluates expressions, or finishes unparsing something, after
 * which it exits.
 */
abstract class TaskCoroutine(val ustate: UState, mainCoroutineArg: => MainCoroutine)
  extends Coroutine[AnyRef]
  with WhereBlockedLocation {

  private lazy val mainCoroutine = mainCoroutineArg // evaluate arg once only

  def doTask(): Unit

  override final protected def run() {
    ???
    try {
      waitForResume
      doTask()
      Assert.invariant(isDone)
      ustate.dataOutputStream.setFinished() // closes it out which will then chain forward to next buffering DOS.
      log(LogLevel.Debug, "%s finished %s.", this, ustate)
      //
      mainCoroutine.isMakingProgress = true
      resumeFinal(mainCoroutine, Suspension.NoData)
      // and since we fall through here, the task thread terminates now.
      // only tasks that aren't completed should hang around in a list
      // or on a data structure, so the coroutine should get picked up by the GC if
      // the task itself is.

    } catch {
      case ie: InterruptedException => // do nothing, mainCoroutine killed us
      case th: Throwable => {
        // tell consumer we're exiting via a throw
        // but not to rethrow it necessarily.
        val ce = new CoroutineException(th)
        resumeFinal(mainCoroutine, ce)
      }
    }
  }
}

/**
 * This object represents the "main" coroutine, the original thread of control to which
 * the corresponding task ultimately always resumes. The task coroutines do transient things like evaluate
 * expressions or finish unparsing something, and then exit, but always resume the main coroutine before doing so.
 */
class MainCoroutine(taskCoroutineArg: => TaskCoroutine)
  extends Coroutine[AnyRef]
  with Logging {

  override final def isMain = true

  private lazy val taskCoroutine = taskCoroutineArg // evaluate arg once only

  final def isDone = taskCoroutine.isDone

  final var isMakingProgress: Boolean = true

  /**
   * After calling this, call isDone and if that's false call isMakingProgress to
   * understand whether it is done, blocked on the exactly same situation, or blocked elsewhere.
   *
   * This status is needed to implement circular deadlock detection
   */
  override final def run() {
    taskCoroutine.doTask()
    // resume(taskCoroutine, Suspension.NoData)
    if (!taskCoroutine.isDone) {

      Assert.invariant(taskCoroutine.isBlocked)
      if (taskCoroutine.isBlockedSameLocation) {
        isMakingProgress = false
      } else if (taskCoroutine.isBlockedFirstTime) {
        isMakingProgress = true
      } else {
        isMakingProgress = true
      }
    } else {
      taskCoroutine.ustate.dataOutputStream.setFinished()
      log(LogLevel.Debug, "%s finished %s.", this, taskCoroutine.ustate)

      // Done. Suspension is completed.
      // TODO: release task object to pool
    }
  }

}

object Suspension {
  object NoData
}

/**
 * A suspension pairs a TaskCoroutine with the main coroutine (i.e., original main thread)
 *
 * The suspension object keeps track of the state of the TaskCoroutine so you can ask
 * the object whether the TaskCoroutine isDone.
 *
 * When executing, the "suspension" (which is a proxy to the task coroutine) may
 * block, by which we mean it may set isDone to false and resume the main coroutine.
 *
 * Resuming the suspension again resumes the task coroutine which loops to retry
 * what caused it to block.
 *
 * This repeats until main is resumed with isDone set to true.
 */
abstract class Suspension(val ustate: UState)
  extends Serializable with Logging {

  protected def mainCoroutine: MainCoroutine

  final def isDone = mainCoroutine.isDone

  protected def taskCoroutine: TaskCoroutine

  def rd: RuntimeData

  /**
   * False if the expression blocked at the same spot, i.e.,
   * didn't make any forward progress.
   */
  final def isMakingProgress = mainCoroutine.isMakingProgress

  final def run() = mainCoroutine.run()

  final def explain() {
    val t = this.taskCoroutine
    Assert.invariant(t.isBlocked)
    log(LogLevel.Warning, "%s", t.blockedLocation)
  }
}

object SuspensionFactory extends SuspensionFactory

class SuspensionFactory extends Logging {

  final def setup(ustate: UState, maybeKnownLengthInBits: MaybeULong): UState = {
    Assert.usage(ustate.currentInfosetNodeMaybe.isDefined)

    val original = ustate.dataOutputStream.asInstanceOf[DirectOrBufferedDataOutputStream]

    /*
     * Pessimistic again. No matter the reason for a suspension, this will split
     * the data output stream so that everything "after" the possible suspension
     * goes into a different data output stream.
     *
     * But there is a very real case where there's no reason for a suspension at
     * all, which is when evaluating the target-length of an element. The target
     * length itself doesn't go out into the data output, so there's no need to
     * split the data input stream.
     *
     * TODO: Performance - Perhaps maybeKnownLengthInBits can be MaybeULong(0) to indicate this
     * situation.
     *
     */

    val buffered = original.addBuffered

    ustate.aaa_debug_DOS.push(buffered) //FIXME: remove. This is a memory leak. Just for debugging

    if (maybeKnownLengthInBits.isDefined) {
      // since we know the length of the unparsed representation that we're skipping for now,
      // that means we know the absolute position of the bits in the buffer we're creating
      // and that means alignment operations don't have to suspend waiting for this knowledge
      if (original.maybeAbsBitPos0b.isDefined) {
        // direct streams always know this, but buffered streams may not.

        val originalAbsBitPos0b = original.maybeAbsBitPos0b.getULong

        // we are passed this length (in bits)
        // and can use it to initialize the absolute bit pos of the buffered output stream.
        //
        // This allows us to deal with alignment regions, that is, we can determine
        // their size since we know the absolute bit position.

        val mkl = maybeKnownLengthInBits.getULong
        buffered.setAbsStartingBitPos0b(originalAbsBitPos0b + mkl)

      }
    } else {
      // log(LogLevel.Debug,
      log(LogLevel.Debug, "SuspensionFactory: %s : Buffered DOS created without knowning absolute start bit pos: %s\n",
        ustate.aaa_currentNode.get.erd.prettyName, buffered)
    }

    //
    // clone the ustate for use when evaluating the expression
    //
    // TODO: Performance - copying this whole state, just for OVC is painful.
    // Some sort of copy-on-write scheme would be better.
    //
    val cloneUState = ustate.cloneForSuspension(original)

    // the main-thread will carry on using the original ustate but unparsing
    // into this buffered stream.
    ustate.dataOutputStream = buffered

    cloneUState
  }
}
