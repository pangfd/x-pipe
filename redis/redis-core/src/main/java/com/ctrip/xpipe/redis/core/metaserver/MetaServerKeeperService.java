package com.ctrip.xpipe.redis.core.metaserver;

import com.ctrip.xpipe.redis.core.entity.KeeperContainerMeta;
import com.ctrip.xpipe.redis.core.entity.KeeperInstanceMeta;
import com.ctrip.xpipe.redis.core.entity.KeeperTransMeta;

import java.util.List;

/**
 * @author wenchao.meng
 *
 *         Aug 2, 2016
 */
public interface MetaServerKeeperService extends MetaServerService {

	public static String PATH_PING = "cluster/{clusterId}/shard/{shardId}/ping";

	public static String PATH_GET_ALL_KEEPERS = "getallkeepers";

	/*********************** for keeper *******************/

//	void ping(String clusterId, String shardId, KeeperInstanceMeta keeperInstanceMeta);

	/*********************** for container *******************/

	/**
	 * meta server merge all meta server's results
	 * 
	 * @param keeperContainerMeta
	 * @return
	 */
//	List<KeeperTransMeta> getAllKeepersByKeeperContainer(KeeperContainerMeta keeperContainerMeta);

	KeeperContainerTokenStatusResponse refreshKeeperContainerTokenStatus(KeeperContainerTokenStatusRequest request);

	public static class KeeperContainerTokenStatusResponse {
		private int tokenSize;
		private boolean close;

		public KeeperContainerTokenStatusResponse(int tokenSize, boolean close) {
			this.tokenSize = tokenSize;
			this.close = close;
		}

		public int getTokenSize() {
			return tokenSize;
		}

		public boolean isClose() {
			return close;
		}

	}

	public static class KeeperContainerTokenStatusRequest {

		private KeeperContainerMeta keeperContainerMeta;

		private int keeperNum;

		private int acked;

		public KeeperContainerTokenStatusRequest(KeeperContainerMeta keeperContainerMeta, int keeperNum, int acked) {
			this.keeperContainerMeta = keeperContainerMeta;
			this.keeperNum = keeperNum;
			this.acked = acked;
		}

		public KeeperContainerMeta getKeeperContainerMeta() {
			return keeperContainerMeta;
		}

		public int getKeeperNum() {
			return keeperNum;
		}

		public int getAcked() {
			return acked;
		}

		@Override
		public String toString() {
			return "KeeperContainerTokenStatusRequest{" +
					"keeperContainerMeta=" + keeperContainerMeta +
					", keeperNum=" + keeperNum +
					", acked=" + acked +
					'}';
		}
	}

}
