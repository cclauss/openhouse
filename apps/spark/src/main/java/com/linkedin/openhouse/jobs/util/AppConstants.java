package com.linkedin.openhouse.jobs.util;

/** Static class to hold constants in Openhouse maintenance modules */
public final class AppConstants {

  public static final String TABLE_NAME = "table_name";
  public static final String JOB_NAME = "job_name";

  // Spark App observability constants
  public static final String TYPE = "type";
  public static final String JOB_TYPE = "job_type";
  public static final String DATA_FILES = "data_files";
  public static final String MANIFEST_FILES = "manifest_files";
  public static final String MANIFEST_LIST_FILES = "manifest_list_files";
  public static final String EXPIRED_FILE_COUNT = "expired_file_count";
  public static final String ORPHAN_FILE_COUNT = "orphan_file_count";
  public static final String STAGED_FILE_COUNT = "staged_file_count";
  public static final String ADDED_DATA_FILE_COUNT = "added_data_file_count";
  public static final String REWRITTEN_DATA_FILE_COUNT = "rewritten_data_file_count";
  public static final String REWRITTEN_DATA_FILE_BYTES = "rewritten_data_file_bytes";
  public static final String REWRITTEN_DATA_FILE_GROUP_COUNT = "rewritten_data_file_group_count";

  // Openhouse jobs status tags
  public static final String STATUS = "status";
  public static final String STATUS_CODE = "status_code";
  public static final String SUCCESS = "success";
  public static final String FAIL = "fail";

  // Openhouse jobs status tag values
  public static final String RUNTIME = "runtime";
  public static final String TIMEOUT = "timeout";
  public static final String OPERATION = "operation";

  // Openhouse jobs observability instruments constants
  public static final String JOB_DURATION = "run_duration";
  public static final String RUN_DURATION_JOB = "run_duration_job";
  public static final String RUN_DURATION_SCHEDULER = "run_duration_scheduler";
  public static final String RUN_COUNT = "run_count";
  public static final String SUCCESSFUL_JOB_COUNT = "succeeded_job_count";
  public static final String FAILED_JOB_COUNT = "failed_job_count";
  public static final String CANCELLED_JOB_COUNT = "cancelled_job_count";

  public static final String JOBS_CLIENT_INITIALIZATION_ERROR = "jobs_client_initialization_error";

  public static final String SERVICE_NAME = "service_name";
  public static final String SERVICE_HOUSETABLES = "housetables";
  public static final String INCOMPATIBLE_DATE_COLUMN = "incompatible_date_column";
  public static final String JOB_ID = "job_id";
  public static final String QUEUED_TIME = "queued_time";
  public static final String DATABASE_NAME = "database_name";

  private AppConstants() {}
}
