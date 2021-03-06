CREATE DATABASE tv1;
CREATE DATABASE tv2;

CREATE TABLE invites (foo INT, bar STRING) PARTITIONED BY (ds STRING);
CREATE VIEW tv1.view1 as SELECT * FROM invites;
DESCRIBE EXTENDED tv1.view1;

ALTER VIEW tv1.view1 RENAME TO tv2.view2;
DESCRIBE EXTENDED tv2.view2;
SELECT * FROM tv2.view2;

DROP TABLE invites;
DROP VIEW tv2.view2;

DROP DATABASE tv1;
DROP DATABASE tv2;
