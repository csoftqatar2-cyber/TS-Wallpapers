-- Applied live as phase3_enforcement_prep on 2026-09-05 (Supabase MCP execute_sql).
--
-- PHASE 3 = the token starts to DECIDE. Nothing here changes any fielded behaviour: the
-- enforcement switch per app starts CLOSED (999999), every existing RPC is untouched, and
-- the new RPC is additive. What this adds is the machinery so the cut-over can later be a
-- settings change (per app, per minimum build) instead of a fleet-wide code change:
--
--   cf.settings 'token_enforce_min_version_code.<app>'  (999999 = v1 still decides)
--       When a car's app_version_code >= this value, licence_status_v2 reports enforced=true
--       and the client is expected to stop falling back to v1. Older builds keep v1 forever.
--
--   public.licence_status_v2(device_hw_id, device_token, app_id, app_version_code) -> jsonb
--       {active_v1, active_v2, has_token, enforced, blocked, status}
--       One round-trip for the next client generation (store 99 / wallpapers 184 / ...):
--       status = 'active' | 'inactive' | 'blocked' — computed as:
--         blocked                      -> 'blocked'
--         enforced & token ok          -> 'active'
--         enforced & token bad/missing -> 'inactive'      <- the only new way to say no,
--                                                           and only for builds >= the switch
--         not enforced                 -> v1's answer (today's behaviour)
--       It never writes anything except the last_verified_at stamp via is_device_activated_v2.
--
--   public.token_adoption (view, admin read) - per app: active cars seen in 30 days, how
--       many hold a token, how many have USED it (last_verified_at within 30 days), and the
--       percentage. The cut-over rule for an app: verified_pct >= 90 for 7 consecutive days,
--       zero unexplained enroll_conflicts, and no support tickets - then set the enforce key
--       to the build that reads the flag.
--
-- ROLLBACK: drop function public.licence_status_v2; drop view public.token_adoption;
--   delete from cf.settings where key like 'token_enforce_min_version_code.%'.

insert into cf.settings(key, value) values
  ('token_enforce_min_version_code.wallpapers', '999999'),
  ('token_enforce_min_version_code.store',      '999999'),
  ('token_enforce_min_version_code.tslink',     '999999'),
  ('token_enforce_min_version_code.backbutton', '999999'),
  ('token_enforce_min_version_code.controller', '999999'),
  ('token_enforce_min_version_code.leo',        '999999')
on conflict (key) do nothing;

create or replace function public.licence_status_v2(device_hw_id text, device_token text default null,
                                                    app_id text default 'wallpapers', app_version_code int default null)
returns jsonb
language plpgsql security definer set search_path to 'public'
as $function$
#variable_conflict use_column
declare
    hw        text;
    v_app     text := lower(coalesce(nullif(btrim(licence_status_v2.app_id), ''), 'wallpapers'));
    v_tok     text := licence_status_v2.device_token;
    v_vc      int  := licence_status_v2.app_version_code;
    d         public.devices;
    enf_vc    int;
    enforced  boolean := false;
    has_tok   boolean := false;
    v1        boolean := false;
    v2        boolean := false;
    st        text;
begin
    if licence_status_v2.device_hw_id is null or btrim(licence_status_v2.device_hw_id) = '' then
        return jsonb_build_object('status','inactive','active_v1',false,'active_v2',false,'has_token',false,'enforced',false,'blocked',false);
    end if;
    hw := public.resolve_device_id(licence_status_v2.device_hw_id);
    select * into d from public.devices dv where dv.hardware_id = hw;

    select s.value::int into enf_vc from cf.settings s where s.key = 'token_enforce_min_version_code.' || v_app;
    enforced := enf_vc is not null and v_vc is not null and v_vc >= enf_vc;

    if found then
        v1 := d.is_active and not d.is_blocked;
        has_tok := exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app);
        if v_tok is not null then
            v2 := public.is_device_activated_v2(licence_status_v2.device_hw_id, v_tok, v_app, v_vc);
        end if;
        if d.is_blocked then st := 'blocked';
        elsif enforced then st := case when v2 then 'active' else 'inactive' end;
        else st := case when v1 then 'active' else 'inactive' end;
        end if;
    else
        st := 'inactive';
    end if;

    return jsonb_build_object(
        'status',    st,
        'active_v1', v1,
        'active_v2', v2,
        'has_token', has_tok,
        'enforced',  enforced,
        'blocked',   coalesce(d.is_blocked, false)
    );
end $function$;
revoke all on function public.licence_status_v2(text, text, text, int) from public;
grant execute on function public.licence_status_v2(text, text, text, int) to anon, authenticated;

create or replace view public.token_adoption as
with apps as (
    select distinct app_id from public.device_tokens
    union select 'store' union select 'wallpapers' union select 'tslink' union select 'backbutton' union select 'controller' union select 'leo'
),
active as (
    select count(*) as n from public.devices d
     where d.is_active and not d.is_blocked and d.last_seen_at >= now() - interval '30 days'
)
select a.app_id,
       (select n from active)                                                                as active_cars_30d,
       count(t.hardware_id)                                                                   as tokens_issued,
       count(t.hardware_id) filter (where t.last_verified_at >= now() - interval '30 days')  as tokens_verified_30d,
       round(100.0 * count(t.hardware_id) filter (where t.last_verified_at >= now() - interval '30 days')
             / greatest((select n from active), 1), 1)                                        as verified_pct,
       count(t.hardware_id) filter (where t.enroll_conflicts > 0)                             as cars_with_conflicts,
       (select s.value from cf.settings s where s.key = 'token_min_version_code.' || a.app_id)      as enrol_gate,
       (select s.value from cf.settings s where s.key = 'token_enforce_min_version_code.' || a.app_id) as enforce_gate
  from apps a
  left join public.device_tokens t on t.app_id = a.app_id
 group by a.app_id;
revoke all on public.token_adoption from public, anon;
grant select on public.token_adoption to authenticated;
