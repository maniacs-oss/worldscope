/**
 * @module SocketAdapter
 */

var rfr = require('rfr');
var Promise = require('bluebird');
var Iron = Promise.promisifyAll(require('iron'));

var Utility = rfr('app/util/Utility');
var Client = rfr('app/adapters/socket/Client');
var ChatRoom = rfr('app/adapters/socket/ChatRoom');
var Authenticator = rfr('app/policies/Authenticator');
var ServerConfig = rfr('config/ServerConfig.js');

var logger = Utility.createLogger(__filename);

function SocketAdapter() {
}

var Class = SocketAdapter.prototype;

Class.init = function init(server) {
  this.io = require('socket.io')(server.listener);

  this.chatRoom = new ChatRoom(server, this.io);

  this.io.on('connection', (socket) => {
    logger.info('New websocket connection from: ' +
                (socket.conn.request.headers['x-forwarded-for'] ||
                 socket.conn.request.connection.remoteAddress));

    socket.on('identify', (cookie) => {
      Iron.unsealAsync(cookie, ServerConfig.cookiePassword, Iron.defaults)
      .then((credentials) => {
        if (!credentials || credentials instanceof Error) {
          logger.error('Error decryping cookie from <identify> message');
          socket.emit('identify', 'ERR');
          return;
        }

        return Authenticator.validateAccount(server, credentials)
        .bind(socketAdapter)
        .then((result) => {
          if (!result || result instanceof Error) {
            return socket.emit('identify', 'ERR');
          }

          try {
            this.chatRoom.addClient(new Client(socket, credentials));
            socket.emit('identify', 'OK');
          } catch (err) {
            logger.error(err);
            socket.emit('identify', 'ERR');
          }
        });
      })
      .catch((err) => {
        socket.emit('identify', 'ERR');
      });
    });
  });
};

var socketAdapter = new SocketAdapter();

module.exports = socketAdapter;
