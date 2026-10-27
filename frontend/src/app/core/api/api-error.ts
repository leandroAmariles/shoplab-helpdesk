export interface ApiError {
  /** `network` when there is no response (backend down); `http` for 4xx/5xx. */
  kind: 'network' | 'http';
  status?: number;
  message: string;
}
