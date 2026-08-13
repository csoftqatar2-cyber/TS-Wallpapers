-- Pre-activation prefetch: let a car start downloading the SHARED library while the
-- activation card is still on screen.
--
-- Why this exists. get_wallpapers answers an unregistered car with the "inactive" sentinel and
-- nothing else, which is the right answer to "what should THIS car show" — that question cannot
-- be answered before the car has an identity. But most of what every car ends up showing is the
-- same shared library, and that part is knowable straight away. Without this the download only
-- began after the serial was accepted and the car type chosen, with a technician and a customer
-- both watching a progress bar start from zero on a workshop connection.
--
-- What it does NOT expose. Nothing device-specific: no per-car wallpapers (is_global = true
-- only), no mode-targeted ones (the mode is not chosen yet), no device rows, no serials, no hide
-- lists. The urls it returns are the public R2 links every activated car already receives, and
-- the objects behind them are publicly readable — so this hands out no access that reading one
-- activated car's playlist would not. The activation gate on get_wallpapers is untouched.
--
-- Capped at 60 rows: this is a head start, not a mirror of the library.

create or replace function public.get_prefetch_wallpapers()
 returns table(url text, type text)
 language sql
 stable                       -- PostgREST only answers GET for a stable/immutable function
 security definer
 set search_path to 'public'
as $function$
    select w.url, w.type
    from public.wallpapers w
    where w.channel = 'app'
      and w.is_global = true
      and w.target_mode is null
    order by w.created_at desc
    limit 60;
$function$;

revoke all on function public.get_prefetch_wallpapers() from public;
grant execute on function public.get_prefetch_wallpapers() to anon, authenticated;
