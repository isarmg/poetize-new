import ReconnectingWebSocket from 'reconnecting-websocket';

/**
 * @param {*} ws_protocol wss or ws
 * @param {*} ip
 * @param {*} port
 * @param {*} protocols WebSocket 子协议列表
 * @param {*} binaryType 'blob' or 'arraybuffer'
 */
export default function (ws_protocol, ip, port, protocols, binaryType) {

  this.ws_protocol = ws_protocol;
  this.ip = ip;
  this.port = port;
  this.protocols = protocols;
  this.binaryType = binaryType;

  if (port === "") {
    this.url = ws_protocol + '://' + ip + '/socket';
  } else {
    this.url = ws_protocol + '://' + ip + ":" + port + '/socket';
  }
  this.connect = () => {
    const ws = new ReconnectingWebSocket(this.url, this.protocols);
    this.ws = ws;
    ws.binaryType = this.binaryType;
  }

  this.send = (data) => {
    this.ws.send(data);
  }

  this.close = () => {
    if (this.ws) {
      this.ws.close(1000, 'page closed');
      this.ws = null;
    }
  }
}
