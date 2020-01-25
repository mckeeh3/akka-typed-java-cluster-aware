
const clusterAware = {

  clusterStateUpdateNode: function (clusterStateFromNode) {
    const selfPort = clusterStateFromNode.selfPort;

    clusterState.members[selfPort - 2551].pingStatistics = clusterStateFromNode.pingStatistics;
  },

  nodeDetails: function (x, y, w, h, nodeNo) {
      const selfPort = 2551 + nodeNo;
      const pingStatistics = clusterState.members[nodeNo].pingStatistics;

      if (pingStatistics) {
          Label().setX(x).setY(y + 2).setW(9).setH(1)
                  .setBorder(0.25)
                  .setKey("Cluster Aware")
                  .setBgColor(color(100, 75))
                  .setKeyColor(color(255, 191, 0))
                  .draw();

          Label().setX(x).setY(y + 3).setW(9).setH(1)
                  .setBorder(0.25)
                  .setKey("Total pings")
                  .setValue(pingStatistics.totalPings)
                  .setKeyColor(color(29, 249, 246))
                  .setValueColor(color(255))
                  .draw();

          var lineY = y + 4;
          for (var p = 0; p < 9; p++) {
              const port = 2551 + p;
              if (pingStatistics.nodePings[port] && port != selfPort) {
                  Label().setX(x).setY(lineY++).setW(9).setH(1)
                          .setBorder(0.25)
                          .setKey("" + port)
                          .setValue(pingStatistics.nodePings[port])
                          .setKeyColor(color(29, 249, 246))
                          .setValueColor(color(255))
                          .draw();

                  const progress = pingStatistics.nodePings[port] % 10;
                  const length = 9 / 10 * (progress == 0 ? 10 : progress);

                  strokeWeight(0);
                  fill(color(29, 249, 246, 30));
                  grid.rect(x, lineY - 0.9, length, 0.7);

                  fill(color(249, 49, 46, 100));
                  grid.rect(x + length - 0.2, lineY - 0.9, 0.2, 0.7);
              }
          }
      }
  },

}
