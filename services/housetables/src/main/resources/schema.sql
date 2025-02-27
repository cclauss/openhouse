-- DDL in this file is in alpha stage and would be subject to change.
CREATE TABLE IF NOT EXISTS user_table_row (
                         database_id         VARCHAR (128)     NOT NULL,
                         table_id            VARCHAR (128)     NOT NULL,
                         version             BIGINT            NOT NULL,
                         metadata_location   VARCHAR (512)     ,
                         last_modified_time  TIMESTAMP         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                         ETL_TS              DATETIME(6)       DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                         PRIMARY KEY (database_id, table_id)
);

-- FIXME: Index is not added at this point.
-- FIXME: Types of timestamp column to be discussed.
CREATE TABLE IF NOT EXISTS job_row (
    job_id                  VARCHAR (128)     NOT NULL,
    state                   VARCHAR (128)     NOT NULL,
    version                 BIGINT            ,
    job_name                VARCHAR (128)     NOT NULL,
    cluster_id              VARCHAR (128)      NOT NULL,
    creation_time_ms        BIGINT ,
    start_time_ms           BIGINT ,
    finish_time_ms          BIGINT ,
    last_update_time_ms     BIGINT ,
    job_conf                MEDIUMTEXT,
    heartbeat_time_ms       BIGINT ,
    execution_id            VARCHAR (128),
    ETL_TS                  datetime(6)      DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (job_id)
    );
