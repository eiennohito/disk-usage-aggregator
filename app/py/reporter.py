import os
import os.path as p
import sys
import socket


class Sender(object):
  """docstring for Sender"""

  def __init__(self, sock, host, port):
    super(Sender, self).__init__()
    self.sock = sock
    self.host = host
    self.port = port
    self.frame_sz = 500
    self.buffer = ""

  def send(self, msg):
    print msg
    if self.can_append(len(msg)):
      self.append(msg)
    else:
      self.flush()
      self.append(msg)

  def flush(self):
    sz = self.sock.sendto(self.buffer, (self.host, self.port))
    print("sent %d bytes" % sz)
    self.buffer = ""

  def can_append(self, ln):
    return len(self.buffer) + ln < self.frame_sz

  def append(self, msg):
    #ln = len(msg)
    self.buffer += msg


def fmt_file(server, path, uid, size):
  return "%s\0%s\0%d\0%d\n" % (server, path, uid, size)


def main():
  args = sys.argv
  print args
  host = args[1]
  port = int(args[2])
  server = args[3]
  target = args[4]

  sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

  sndr = Sender(sock, host, port)

  def recurse(trg):
    chldr = os.listdir(trg)
    totsize = 0
    for cp in chldr:
      pth = p.join(trg, cp)
      sobj = p.isdir(pth)
      sz = p.getsize(pth)
      if sobj:
        totsize += recurse(pth)
      totsize += sz

    # print("%s %d" % (trg, totsize))
    st = os.stat(trg)
    print((server, trg, st.st_uid, totsize))
    sndr.send(fmt_file(server, trg, st.st_uid, totsize))
    return totsize

  recurse(target)
  sndr.flush()


if __name__ == '__main__':
  main()
