package org.refptr.iscala

import sun.misc.{Signal,SignalHandler}

import org.zeromq.ZMQ

import scalax.io.JavaConverters._
import scalax.file.Path

import Util.{getpid,log,debug}
import json.JsonUtil._
import msg._

object IScala extends App with Parent {
    val options = new Options(args)

    val profile = options.profile match {
        case Some(path) => Path(path).string.as[Profile]
        case None =>
            val file = Path(s"profile-${getpid()}.json")
            log(s"connect ipython with --existing ${file.toAbsolute.path}")
            val profile = Profile.default
            file.write(toJSON(profile))
            profile
    }

    val zmq = new Sockets(profile)
    val ipy = new Communication(zmq, profile)

    lazy val interpreter = new Interpreter(options.tail)

    var executeMsg: Option[Msg[Request]] = None

    def welcome() {
        import scala.util.Properties._
        log(s"Welcome to Scala $versionNumberString ($javaVmName, Java $javaVersion)")
    }

    Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
            log("Terminating IScala")
        }
    })

    Signal.handle(new Signal("INT"), new SignalHandler {
        private var previously: Long = 0

        def handle(signal: Signal) {
            interpreter.cancel()

            if (!options.parent) {
                val now = System.currentTimeMillis
                if (now - previously < 500) sys.exit() else previously = now
            }
        }
    })

    class WatchStream(std: Std) extends Thread {
        override def run() {
            val size = 10240
            val buffer = new Array[Byte](size)

            while (true) {
                val n = std.input.read(buffer)

                executeMsg.foreach {
                    ipy.send_stream(_, std.name, new String(buffer.take(n)))
                }

                if (n < size) {
                    Thread.sleep(100) // a little delay to accumulate output
                }
            }
        }
    }

    class HeartBeat extends Thread {
        override def run() {
            ZMQ.proxy(zmq.heartbeat, zmq.heartbeat, null)
        }
    }

    val ExecuteHandler = new ExecuteHandler(this)
    val CompleteHandler = new CompleteHandler(this)
    val KernelInfoHandler = new KernelInfoHandler(this)
    val ObjectInfoHandler = new ObjectInfoHandler(this)
    val ConnectHandler = new ConnectHandler(this)
    val ShutdownHandler = new ShutdownHandler(this)
    val HistoryHandler = new HistoryHandler(this)

    class EventLoop(socket: ZMQ.Socket) extends Thread {
        override def run() {
            while (true) {
                val msg = try {
                    Some(ipy.recv(socket))
                } catch {
                    case e: play.api.libs.json.JsResultException =>
                        Util.log(s"JSON deserialization error: ${e.getMessage}")
                        None
                }

                msg.foreach { msg =>
                    msg.header.msg_type match {
                        case MsgType.execute_request => ExecuteHandler(socket, msg.asInstanceOf[Msg[execute_request]])
                        case MsgType.complete_request => CompleteHandler(socket, msg.asInstanceOf[Msg[complete_request]])
                        case MsgType.kernel_info_request => KernelInfoHandler(socket, msg.asInstanceOf[Msg[kernel_info_request]])
                        case MsgType.object_info_request => ObjectInfoHandler(socket, msg.asInstanceOf[Msg[object_info_request]])
                        case MsgType.connect_request => ConnectHandler(socket, msg.asInstanceOf[Msg[connect_request]])
                        case MsgType.shutdown_request => ShutdownHandler(socket, msg.asInstanceOf[Msg[shutdown_request]])
                        case MsgType.history_request => HistoryHandler(socket, msg.asInstanceOf[Msg[history_request]])
                    }
                }
            }
        }
    }

    (new WatchStream(StdOut)).start()
    (new WatchStream(StdErr)).start()

    val heartBeat = new HeartBeat
    heartBeat.start()

    ipy.send_status(ExecutionState.starting)

    debug("Starting kernel event loops")

    (new EventLoop(zmq.requests)).start()
    (new EventLoop(zmq.control)).start()

    welcome()
    heartBeat.join()
}
