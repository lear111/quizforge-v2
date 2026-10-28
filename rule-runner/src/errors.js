export class RunnerError extends Error {
  constructor(code, message, status = 200) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export const failure = (code = 'RULE_REJECTED', message = 'Extension rules rejected or could not process this data') => ({ok: false, code, message});
export const LIMITS = Object.freeze({input: 8 * 1024 * 1024, asset: 1024 * 1024, output: 2 * 1024 * 1024});
