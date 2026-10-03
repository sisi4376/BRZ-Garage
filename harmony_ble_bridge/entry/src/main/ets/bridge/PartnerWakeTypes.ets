export interface PartnerWakeSnapshot {
  supported: boolean;
  registered: boolean;
  scanning: boolean;
  status: string;
  deviceAddress: string;
  error: string;
  diagnostics: string;
}

export type PartnerWakeListener = (snapshot: PartnerWakeSnapshot) => void;
