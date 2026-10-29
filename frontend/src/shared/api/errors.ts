export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public fields: { field: string; message: string }[] = [],
    public type?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
