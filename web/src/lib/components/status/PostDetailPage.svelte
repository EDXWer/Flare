<script lang="ts">
	import { page } from '$app/state';
	import AppBackButton from '$lib/components/AppBackButton.svelte';
	import AppTopBar from '$lib/components/AppTopBar.svelte';
	import FaIcon from '$lib/components/FaIcon.svelte';
	import { localizedUiText } from '$lib/i18n/uiStrings';
	import { m } from '$lib/paraglide/messages.js';
	import TimelineItem from '$lib/components/UiTimeline/TimelineItem.svelte';
	import TimelineList from '$lib/components/UiTimeline/TimelineList.svelte';
	import { useReactiveRetainedPresenter } from '$lib/presenter/presenterStore.svelte';
	import {
		createStatusContextPresenterController,
		type AccountType,
		type MicroBlogKey,
	} from '@flare/web-presenters/statusContext.svelte';

	const routeAccountKey = $derived(page.params.accountKey ?? 'guest');
	const routeStatusKey = $derived(page.params.statusKey ?? '');
	const accountType = $derived(parseAccountType(routeAccountKey));
	const detailStatusKey = $derived(parseMicroBlogKey(routeStatusKey));
	const presenterKey = $derived(`${routeAccountKey}:${routeStatusKey}`);
	const statusState = useReactiveRetainedPresenter(
		() => `status-detail:${presenterKey}`,
		() => createStatusContextPresenterController(accountType, detailStatusKey),
		{ ttlMs: Infinity }
	);
	const currentError = $derived(
		statusState.current.type === 'Error' ? (statusState.current.message ?? m.statusUnableToLoadPost()) : null
	);
	const listError = $derived(
		statusState.listState.type === 'Error'
			? (statusState.listState.message ?? m.statusUnableToLoadConversation())
			: null
	);
	const isRefreshing = $derived(
		statusState.listState.type === 'Success' ? statusState.listState.isRefreshing : false
	);

	function parseAccountType(value: string): AccountType {
		if (value === 'guest') return { type: 'Guest' };
		if (value.startsWith('guest@')) {
			return { type: 'GuestHost', host: value.slice('guest@'.length) };
		}
		return { type: 'Specific', accountKey: parseMicroBlogKey(value) };
	}

	function parseMicroBlogKey(value: string): MicroBlogKey {
		let escaping = false;
		let idFinished = false;
		let id = '';
		let host = '';

		for (const char of value) {
			if (escaping) {
				if (idFinished) host += char;
				else id += char;
				escaping = false;
				continue;
			}

			if (char === '\\') {
				escaping = true;
				continue;
			}

			if (char === '@' && !idFinished) {
				idFinished = true;
				continue;
			}

			if (char === ',') break;

			if (idFinished) host += char;
			else id += char;
		}

		return { id, host };
	}
</script>

<section class="min-h-screen bg-base-100">
	<AppTopBar title={m.statusDetailTitle()}>
		{#snippet start()}
			<AppBackButton />
		{/snippet}

		{#snippet end()}
			<button
				class="btn btn-ghost btn-square btn-sm rounded-box"
				type="button"
				aria-label={m.actionRetry()}
				title={m.actionRetry()}
				onclick={() => statusState.refreshAsync()}
			>
				<FaIcon name="Reset" size={15} />
			</button>
		{/snippet}

		{#snippet bottom()}
			{#if isRefreshing}
				<progress class="progress progress-primary block h-0.5 w-full"></progress>
			{/if}
		{/snippet}
	</AppTopBar>

	<div class="min-h-[calc(100vh-3.5rem)] w-full bg-base-100">
		{#each statusState.header as item}
			<article class="border-b border-base-300 px-4">
				<TimelineItem {item} {detailStatusKey} />
			</article>
		{/each}

		{#if statusState.tabs.length > 1 || statusState.filters.length}
			<div class="sticky top-14 z-10 flex min-w-0 items-center gap-2 border-b border-base-300 bg-base-100 px-3">
				{#if statusState.tabs.length > 1}
					<div class="tabs tabs-border min-w-0 flex-1 flex-nowrap overflow-x-auto" role="tablist" aria-label={m.statusDetailTabs()}>
						{#each statusState.tabs as tab (tab.id)}
							<button
								class="tab min-h-12 whitespace-nowrap"
								class:tab-active={statusState.selectedTabId === tab.id}
								type="button"
								role="tab"
								aria-selected={statusState.selectedTabId === tab.id}
								onclick={() => statusState.selectTab(tab.id)}
							>{localizedUiText(tab.title)}</button>
						{/each}
					</div>
				{/if}
				{#if statusState.filters.length}
					<select
						class="select select-sm my-2 ml-auto w-auto max-w-40 shrink-0"
						aria-label={m.statusDetailFilter()}
						value={statusState.selectedFilterId ?? ''}
						onchange={(event) => statusState.selectFilter(event.currentTarget.value)}
					>
						{#each statusState.filters as filter (filter.id)}
							<option value={filter.id}>{localizedUiText(filter.title)}</option>
						{/each}
					</select>
				{/if}
			</div>
		{/if}

		{#if currentError || listError}
			<div class="grid gap-3 p-4">
				<div class="alert alert-error">
					<span>{currentError ?? listError}</span>
				</div>
				<button class="btn btn-primary btn-sm rounded-box justify-self-start" type="button" onclick={() => currentError ? statusState.refreshAsync() : statusState.retryChildren()}>
					{m.actionRetry()}
				</button>
			</div>
		{:else if statusState.listState.type === 'Empty' && statusState.selectedFilterId !== statusState.defaultFilterId}
			<p class="p-6 text-center text-base-content/65">{m.statusDetailFilterEmpty()}</p>
		{:else if statusState.listState.type !== 'Empty' || statusState.tabs.length || !statusState.header.length}
			{#key JSON.stringify([statusState.selectedTabId, statusState.selectedFilterId])}
				<TimelineList listState={statusState.listState} {detailStatusKey} />
			{/key}
		{/if}
	</div>
</section>
