# Gym log CSV for Heartline

Heartline reads this CSV (and the older exports) from **Activity → Gym log → Import log files**. Timed sets merge into a
Heartline recording made at the same time: your sets, names and rest times replace the watch's guesses, and the watch
adds heart rate and rep counts. Treadmill rows with speed and incline give a VO2max estimate, and their display figures
(time, distance, steps, calories) are used ahead of the watch's own on the session page.

## Columns

One row per set, in the order done. Warm-ups, skipped sets and treadmill stretches get rows too.

| Column | Values | Notes |
|---|---|---|
| `session_id` | `2026-11-09_pull` | Starts with the date the session began, even after midnight. Keep it the same in every export of a session. |
| `session_name` | `Pull day` | Free text. |
| `exercise` | `Lat pulldown` | Use the same name every time for the same machine or lift, so sessions compare. |
| `exercise_type` | `strength`, `bodyweight` or `cardio` | Treadmill, bike and rower are `cardio`. |
| `set_number` | `0`, `1`, `2`… | `0` for a warm-up. |
| `set_type` | `warmup` or `working` | |
| `status` | `completed` or `skipped` | |
| `start_datetime_ist` | `2026-11-09T22:15:00+05:30` | ISO 8601 with the `+05:30` offset. The moment you say you're starting. **Required for the merge.** |
| `end_datetime_ist` | `2026-11-09T22:15:48+05:30` | The moment you say you're done. **Required for the merge.** |
| `weight_kg` | `55` | The number on the stack, plate or dumbbell. Blank for cardio. |
| `weight_type` | `external_load`, `assistance`, `per_side` or `bodyweight` | `assistance` = assisted machine (weight comes off body weight); `per_side` = plates on each side (counted twice); `bodyweight` = body weight plus `weight_kg` (belt or plate; 0 or blank for none). |
| `reps_reported` | `8`, `7.5` | Decimals for a partial last rep. |
| `reached_failure` | `True` or `False` | |
| `reps_in_reserve` | `2` | Optional. |
| `difficulty_out_of_10` | `7` | Optional. |
| `rest_before_seconds` | `120` | Optional; Heartline works it out from the times when blank. |
| `speed_kmh` | `3.6` | Cardio only. The belt speed held for the stretch. |
| `incline_pct` | `20` | Cardio only. `0` for flat, never blank on a treadmill row. |
| `distance_km` | `0.61` | Cardio only, from the treadmill display. Optional. |
| `duration_seconds` | `600` | Cardio only, the time on the treadmill display. Optional; from the start and end times when blank. |
| `steps` | `986` | Cardio only, the step count on the treadmill display. Optional. |
| `calories_kcal` | `45` | Cardio only, the calories on the treadmill display. Optional. |
| `handrails` | `none`, `some` or `held` | Treadmill only. `held` rows are left out of VO2max. |
| `notes` | free text | Quote it if it has a comma. |

For a treadmill stretch where you change speed or incline, start a new row (set 2, 3…) at each change, with its own
start and end time.

## Paste into a logging chat

> Log my gym session. When I say I'm starting a set, note the exact time; when I say I'm done, note that time too.
> Ask me for the reps, the weight and whether it's a warm-up if I don't say. For the treadmill, ask for the speed,
> incline and whether I held the handrails, and start a new row whenever I change speed or incline. When I finish on
> the treadmill, ask for what its display shows: time, distance, steps and calories.
> When I ask for the export, give me one CSV with exactly these columns, in this order:
> `session_id,session_name,exercise,exercise_type,set_number,set_type,status,start_datetime_ist,end_datetime_ist,weight_kg,weight_type,reps_reported,reached_failure,reps_in_reserve,difficulty_out_of_10,rest_before_seconds,speed_kmh,incline_pct,distance_km,duration_seconds,steps,calories_kcal,handrails,notes`
> Rules: one row per set in the order done, including warm-ups (set_number 0, set_type warmup) and skipped sets
> (status skipped); times as ISO 8601 with +05:30 to the second; session_id is the session's start date then an
> underscore and a short name, the same in every export; exercise_type is strength, bodyweight or cardio;
> weight_type is external_load, assistance, per_side or bodyweight; reached_failure is True or False; leave a cell
> empty when unknown, never write "n/a"; quote any text with a comma.

## Example

```csv
session_id,session_name,exercise,exercise_type,set_number,set_type,status,start_datetime_ist,end_datetime_ist,weight_kg,weight_type,reps_reported,reached_failure,reps_in_reserve,difficulty_out_of_10,rest_before_seconds,speed_kmh,incline_pct,distance_km,duration_seconds,steps,calories_kcal,handrails,notes
2026-11-09_pull,Pull day,Assisted pull-up,bodyweight,0,warmup,completed,2026-11-09T22:12:10+05:30,2026-11-09T22:12:52+05:30,60,assistance,8,False,,,,,,,,,,,
2026-11-09_pull,Pull day,Assisted pull-up,bodyweight,1,working,completed,2026-11-09T22:15:00+05:30,2026-11-09T22:15:48+05:30,55,assistance,8,False,2,7,128,,,,,,,,
2026-11-09_pull,Pull day,Treadmill incline walk,cardio,1,working,completed,2026-11-09T23:00:00+05:30,2026-11-09T23:10:00+05:30,,,,,,,,3.6,20,0.61,600,960,44,none,
```
