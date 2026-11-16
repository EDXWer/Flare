<script lang="ts">
	import { m } from '$lib/paraglide/messages.js';
	import type { AiRequestHeader } from '@flare/web-presenters/aiConfig.svelte';

	let { headers, onSave }: { headers: AiRequestHeader[]; onSave: (headers: AiRequestHeader[]) => boolean } =
		$props();
	let draft = $state<AiRequestHeader[] | null>(null);
	let invalid = $state(false);

	function close() {
		draft = null;
		invalid = false;
	}
</script>

{#if draft}
	<form class="grid gap-3" onsubmit={(event) => {
		event.preventDefault();
		if (!draft) return;
		if (onSave(draft)) close();
		else invalid = true;
	}}>
		<fieldset class="grid gap-3">
			<legend class="label-text mb-2">{m.settingsAiCustomHeaders()}</legend>
			{#each draft as header, index}
				<div class="grid gap-2 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
					<label class="form-control">
						<span class="label-text">{m.settingsAiHeaderName()}</span>
						<input class="input input-bordered w-full" type="text" spellcheck={false} bind:value={header.name} />
					</label>
					<label class="form-control">
						<span class="label-text">{m.settingsAiHeaderValue()}</span>
						<input class="input input-bordered w-full" type="text" autocomplete="off" spellcheck={false} bind:value={header.value} />
					</label>
					<button class="btn btn-sm" type="button" onclick={() => { draft?.splice(index, 1); }}>
						{m.actionDelete()}
					</button>
				</div>
			{/each}
			{#if invalid}
				<p class="text-error text-sm" role="alert">{m.settingsAiHeadersInvalid()}</p>
			{/if}
		</fieldset>
		<div class="flex flex-wrap items-center gap-2">
			<button class="btn btn-sm" type="button" onclick={() => { draft?.push({ name: '', value: '' }); }}>
				{m.settingsAiHeaderAdd()}
			</button>
			<button class="btn btn-sm btn-ghost ml-auto" type="button" onclick={close}>{m.actionCancel()}</button>
			<button class="btn btn-sm btn-primary" type="submit">{m.tabSettingsSave()}</button>
		</div>
	</form>
{:else}
	<div class="flex items-center justify-between gap-3">
		<div class="grid min-w-0 gap-1">
			<span class="label-text">{m.settingsAiCustomHeaders()}</span>
			{#if headers.length}
				<span class="text-base-content/70 truncate text-sm">{headers.map((header) => header.name).join(', ')}</span>
			{/if}
		</div>
		<button class="btn btn-sm" type="button" onclick={() => { draft = headers.map((header) => ({ ...header })); }}>
			{m.tabSettingsEdit()}
		</button>
	</div>
{/if}
