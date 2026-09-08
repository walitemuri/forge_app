export interface ForgeWorker {
  id: string;
  sessionId: string;
  hostname: string;
  operatingSystem: string;
  cpuCores: number;
  memoryBytes: number;
  cpuUsagePercent: number;
  memoryUsedBytes: number;
  runningTasks: number;
  outstandingTasks: number;
  capacity: number;
  online: boolean;
  commandStreamConnected: boolean;
  lastHeartbeat: number;
}

export interface ForgeWorkflow {
  id: string;
  name: string;
  createdAt: string;
  status: string;
  taskCount: number;
}

export interface ForgeWorkflowTask {
  key: string;
  taskId: string;
  status: string;
  dependsOn: string[];
}

export interface ForgeWorkflowDetail {
  id: string;
  name: string;
  createdAt: string;
  status: string;
  tasks: ForgeWorkflowTask[];
}

export interface ForgeExecutionEvent {
  id: number;
  type: string;
  workflowId: string;
  taskId: string | null;
  attemptId: string | null;
  workerId: string | null;
  message: string;
  createdAt: string;
}
