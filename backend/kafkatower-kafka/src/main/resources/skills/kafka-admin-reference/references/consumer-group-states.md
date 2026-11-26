# Kafka Admin Reference — Consumer Group State

Read-only. Tool names and parameters are in the tool list. This holds only what tools do not say.

- `Stable`: healthy.
- `PreparingRebalance` / `CompletingRebalance`: transitioning. Problem only if persistent.
- `Empty`: no members. Check if intentional.
- `Dead`: coordinator lost or group deleted.
