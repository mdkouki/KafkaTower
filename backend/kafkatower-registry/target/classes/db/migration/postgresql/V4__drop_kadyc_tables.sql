-- KADYC import (Cluster JSON, topic ownership) is retired: per-user Kafka access is now
-- computed live from ACLs instead (see AclGraphBuilder/AclGraphStore), so these tables have
-- no reader left. Dropped rather than left behind — never edit V3, this is a new migration.

DROP TABLE IF EXISTS topic_access_controls;
DROP TABLE IF EXISTS clusters;
