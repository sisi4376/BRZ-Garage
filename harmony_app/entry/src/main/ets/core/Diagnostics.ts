// Only fixed stage labels and source locations leave the error object. Messages can contain user data.
export function diagnosticFrames(stack: string): string {
  return stack.split('\n').filter((line: string) => /^\s*at\s/.test(line))
    .map((line: string) => line.match(/(?:[A-Za-z0-9_-]+\.(?:ets|ts|js)):\d+:\d+/)?.[0] || '')
    .filter((line: string) => !!line).slice(0, 20).join('\n');
}
export function diagnosticReport(version: string, stack: string, stages: string[]): string {
  return ('BRZ Harmony local diagnostic v1\nApp: ' + version + '\nTime: ' + Date.now() +
    '\nStages:\n' + stages.slice(-24).join('\n') + '\nSource locations (messages omitted):\n' + diagnosticFrames(stack)).slice(0, 12000);
}
