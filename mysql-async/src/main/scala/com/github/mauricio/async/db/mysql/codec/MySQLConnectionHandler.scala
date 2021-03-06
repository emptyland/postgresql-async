/*
 * Copyright 2013 Maurício Linhares
 *
 * Maurício Linhares licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.github.mauricio.async.db.mysql.codec

import com.github.mauricio.async.db.Configuration
import com.github.mauricio.async.db.general.MutableResultSet
import com.github.mauricio.async.db.mysql.binary.BinaryRowDecoder
import com.github.mauricio.async.db.mysql.message.client._
import com.github.mauricio.async.db.mysql.message.server._
import com.github.mauricio.async.db.mysql.util.CharsetMapper
import com.github.mauricio.async.db.util.ChannelFutureTransformer.toFuture
import com.github.mauricio.async.db.util._
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBufAllocator
import io.netty.channel._
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.CodecException
import java.net.InetSocketAddress
import scala.Some
import scala.annotation.switch
import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.concurrent._

class MySQLConnectionHandler(
                              configuration: Configuration,
                              charsetMapper: CharsetMapper,
                              handlerDelegate: MySQLHandlerDelegate,
                              group : EventLoopGroup,
                              executionContext : ExecutionContext,
                              connectionId : String
                              )
  extends SimpleChannelInboundHandler[Object] {

  private implicit val internalPool = executionContext
  private final val log = Log.getByName(s"[connection-handler]${connectionId}")
  private final val bootstrap = new Bootstrap().group(this.group)
  private final val connectionPromise = Promise[MySQLConnectionHandler]
  private final val decoder = new MySQLFrameDecoder(configuration.charset, connectionId)
  private final val encoder = new MySQLOneToOneEncoder(configuration.charset, charsetMapper)
  private final val currentParameters = new ArrayBuffer[ColumnDefinitionMessage]()
  private final val currentColumns = new ArrayBuffer[ColumnDefinitionMessage]()
  private final val parsedStatements = new HashMap[String,PreparedStatementHolder]()
  private final val binaryRowDecoder = new BinaryRowDecoder()

  private var currentPreparedStatementHolder : PreparedStatementHolder = null
  private var currentPreparedStatement : PreparedStatementMessage = null
  private var currentQuery : MutableResultSet[ColumnDefinitionMessage] = null
  private var currentContext: ChannelHandlerContext = null

  def connect: Future[MySQLConnectionHandler] = {
    this.bootstrap.channel(classOf[NioSocketChannel])
    this.bootstrap.handler(new ChannelInitializer[io.netty.channel.Channel]() {

      override def initChannel(channel: io.netty.channel.Channel): Unit = {
        channel.pipeline.addLast(
          decoder,
          encoder,
          MySQLConnectionHandler.this)
      }

    })

    this.bootstrap.option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    this.bootstrap.option[ByteBufAllocator](ChannelOption.ALLOCATOR, LittleEndianByteBufAllocator.INSTANCE)

    this.bootstrap.connect(new InetSocketAddress(configuration.host, configuration.port)).onFailure {
      case exception => this.connectionPromise.tryFailure(exception)
    }

    this.connectionPromise.future
  }

  override def channelRead0(ctx: ChannelHandlerContext, message: Object) {

    //log.debug("Message received {}", message)

    message match {
      case m: ServerMessage => {
        (m.kind: @switch) match {
          case ServerMessage.ServerProtocolVersion => {
            handlerDelegate.onHandshake(m.asInstanceOf[HandshakeMessage])
          }
          case ServerMessage.Ok => {
            this.clearQueryState
            handlerDelegate.onOk(m.asInstanceOf[OkMessage])
          }
          case ServerMessage.Error => {
            this.clearQueryState
            handlerDelegate.onError(m.asInstanceOf[ErrorMessage])
          }
          case ServerMessage.EOF => {

            val resultSet = this.currentQuery
            this.clearQueryState

            if ( resultSet != null ) {
              handlerDelegate.onResultSet( resultSet, m.asInstanceOf[EOFMessage] )
            } else {
              handlerDelegate.onEOF(m.asInstanceOf[EOFMessage])
            }

          }
          case ServerMessage.ColumnDefinition => {
            val message = m.asInstanceOf[ColumnDefinitionMessage]

            if ( currentPreparedStatementHolder != null && this.currentPreparedStatementHolder.needsAny ) {
              this.currentPreparedStatementHolder.add(message)
            }

            this.currentColumns += message
          }
          case ServerMessage.ColumnDefinitionFinished => {
            this.onColumnDefinitionFinished()
          }
          case ServerMessage.PreparedStatementPrepareResponse => {
            this.onPreparedStatementPrepareResponse(m.asInstanceOf[PreparedStatementPrepareResponse])
          }
          case ServerMessage.Row => {
            val message = m.asInstanceOf[ResultSetRowMessage]
            val items = new Array[Any](message.size)

            var x = 0
            while ( x < message.size ) {
              items(x) = if ( message(x) == null ) {
                null
              } else {
                val columnDescription = this.currentQuery.columnTypes(x)
                columnDescription.textDecoder.decode(columnDescription, message(x), configuration.charset)
              }
              x += 1
            }

            this.currentQuery.addRow(items)
          }
          case ServerMessage.BinaryRow => {
            val message = m.asInstanceOf[BinaryRowMessage]
            this.currentQuery.addRow( this.binaryRowDecoder.decode(message.buffer, this.currentColumns ))
          }
          case ServerMessage.ParamProcessingFinished => {
          }
          case ServerMessage.ParamAndColumnProcessingFinished => {
            this.onColumnDefinitionFinished()
          }
        }
      }
    }

  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    handlerDelegate.connected(ctx)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    // unwrap CodecException if needed
    cause match {
      case t: CodecException => handleException(t.getCause)
      case _ =>  handleException(cause)
    }

  }

  private def handleException(cause: Throwable) {
    if (!this.connectionPromise.isCompleted) {
      this.connectionPromise.failure(cause)
    }
    handlerDelegate.exceptionCaught(cause)
  }

  override def handlerAdded(ctx: ChannelHandlerContext) {
    this.currentContext = ctx
  }

  def write( message : QueryMessage ) : ChannelFuture = {
    this.decoder.queryProcessStarted()
    writeAndHandleError(message)
  }

  def write( message : PreparedStatementMessage )  {

    this.currentColumns.clear()
    this.currentParameters.clear()

    this.currentPreparedStatement = message

    this.parsedStatements.get(message.statement) match {
      case Some( item ) => {
        this.executePreparedStatement(item.statementId, item.columns.size, message.values, item.parameters)
      }
      case None => {
        decoder.preparedStatementPrepareStarted()
        writeAndHandleError( new PreparedStatementPrepareMessage(message.statement) )
      }
    }
  }

  def write( message : HandshakeResponseMessage ) : ChannelFuture = {
    writeAndHandleError(message)
  }

  def write( message : QuitMessage ) : ChannelFuture = {
    writeAndHandleError(message)
  }

  def disconnect: ChannelFuture = this.currentContext.close()

  def clearQueryState {
    this.currentColumns.clear()
    this.currentParameters.clear()
    this.currentQuery = null
  }

  def isConnected : Boolean = {
    if ( this.currentContext != null ) {
      this.currentContext.channel.isActive
    } else {
      false
    }
  }

  private def executePreparedStatement( statementId : Array[Byte], columnsCount : Int, values : Seq[Any], parameters : Seq[ColumnDefinitionMessage] ) {
    decoder.preparedStatementExecuteStarted(columnsCount, parameters.size)
    this.currentColumns.clear()
    this.currentParameters.clear()
    writeAndHandleError(new PreparedStatementExecuteMessage( statementId, values, parameters ))
  }

  private def onPreparedStatementPrepareResponse( message : PreparedStatementPrepareResponse ) {
    this.currentPreparedStatementHolder = new PreparedStatementHolder( this.currentPreparedStatement.statement, message)
  }

  def onColumnDefinitionFinished() {

    val columns = if ( this.currentPreparedStatementHolder != null ) {
      this.currentPreparedStatementHolder.columns
    } else {
      this.currentColumns
    }

    this.currentQuery = new MutableResultSet[ColumnDefinitionMessage](columns)

    if ( this.currentPreparedStatementHolder != null ) {
      this.parsedStatements.put( this.currentPreparedStatementHolder.statement, this.currentPreparedStatementHolder )
      this.executePreparedStatement(
        this.currentPreparedStatementHolder.statementId,
        this.currentPreparedStatementHolder.columns.size,
        this.currentPreparedStatement.values,
        this.currentPreparedStatementHolder.parameters
      )
      this.currentPreparedStatementHolder = null
      this.currentPreparedStatement = null
    }
  }

  private def writeAndHandleError( message : Any ) : ChannelFuture =  {
    val future = this.currentContext.writeAndFlush(message)

    future.onFailure {
      case e : Throwable => handleException(e)
    }

    future
  }

}
