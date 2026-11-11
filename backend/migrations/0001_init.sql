-- Mina Phase 2 schema (Supabase). Apply with:
--   supabase db push   OR   psql "$DATABASE_URL" -f migrations/0001_init.sql
create extension if not exists pgcrypto;

create or replace function public.set_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end $$;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text unique,
  display_name text,
  role text not null default 'user' check (role in ('user','admin')),
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create trigger trg_profiles_updated before update on public.profiles
  for each row execute function public.set_updated_at();

create table public.conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  title text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_conversations_user on public.conversations(user_id);
create trigger trg_conversations_updated before update on public.conversations
  for each row execute function public.set_updated_at();

create table public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null check (role in ('user','assistant','system')),
  content text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_messages_user on public.messages(user_id);
create index ix_messages_conversation on public.messages(conversation_id);
create trigger trg_messages_updated before update on public.messages
  for each row execute function public.set_updated_at();

create table public.provider_keys (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  provider text not null,
  label text,
  key_value text not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_provider_keys_user_provider on public.provider_keys(user_id, provider);
create trigger trg_provider_keys_updated before update on public.provider_keys
  for each row execute function public.set_updated_at();

create table public.composio_connections (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  provider text not null,
  account_id text,
  status text not null default 'connected',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_composio_connections_user on public.composio_connections(user_id);
create trigger trg_composio_updated before update on public.composio_connections
  for each row execute function public.set_updated_at();

create table public.tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  title text not null,
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'queued',
  result text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_tasks_user_status on public.tasks(user_id, status);
create trigger trg_tasks_updated before update on public.tasks
  for each row execute function public.set_updated_at();

create table public.memories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null default 'fact',
  content text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_memories_user on public.memories(user_id);
create trigger trg_memories_updated before update on public.memories
  for each row execute function public.set_updated_at();

create table public.usage_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  model text,
  prompt_tokens integer not null default 0,
  completion_tokens integer not null default 0,
  cost_cents integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_usage_logs_user_created on public.usage_logs(user_id, created_at);
create trigger trg_usage_logs_updated before update on public.usage_logs
  for each row execute function public.set_updated_at();

create table public.approvals (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  task_id uuid references public.tasks(id) on delete cascade,
  status text not null default 'pending',
  request jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index ix_approvals_user on public.approvals(user_id);
create trigger trg_approvals_updated before update on public.approvals
  for each row execute function public.set_updated_at();

-- Row Level Security on every user-owned table. The service role (used
-- only server-side) bypasses RLS; anon/authenticated clients can never
-- touch another tenant's rows even if a key leaks.
alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.messages enable row level security;
alter table public.provider_keys enable row level security;
alter table public.composio_connections enable row level security;
alter table public.tasks enable row level security;
alter table public.memories enable row level security;
alter table public.usage_logs enable row level security;
alter table public.approvals enable row level security;

create policy "profiles are self rows" on public.profiles
  for all using (id = auth.uid()) with check (id = auth.uid());
create policy "conversations are tenant scoped" on public.conversations
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "messages are tenant scoped" on public.messages
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "provider keys are tenant scoped" on public.provider_keys
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "composio connections are tenant scoped" on public.composio_connections
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "tasks are tenant scoped" on public.tasks
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "memories are tenant scoped" on public.memories
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "usage logs are tenant scoped" on public.usage_logs
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "approvals are tenant scoped" on public.approvals
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

-- Profiles are provisioned automatically when a Supabase user signs up.
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, email) values (new.id, new.email);
  return new;
end $$;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();
