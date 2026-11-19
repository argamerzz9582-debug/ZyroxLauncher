"""Run locally: pip install 'discord.py>=2.4,<3'; python scripts/setup-discord.py"""
import asyncio
import getpass
import os

import discord


async def main():
    token = os.getenv('DISCORD_TOKEN') or getpass.getpass('New bot token (hidden): ')
    async with discord.Client(intents=discord.Intents.none()) as bot:
        await bot.login(token)
        guild = await bot.fetch_guild(1573020729371398144)
        if os.getenv('CONFIRMED_GUILD_ID') != str(guild.id) and input(f'Apply Zyrox setup to {guild.name} ({guild.id})? Type YES: ') != 'YES':
            return
        roles = await guild.fetch_roles()
        staff = []
        for name in ('Maintainer', 'Moderator', 'Helper', 'Release Ping', 'Beta Ping'):
            role = discord.utils.get(roles, name=name)
            if role is None:
                role = await guild.create_role(name=name, permissions=discord.Permissions.none())
            if name in ('Maintainer', 'Moderator'):
                staff.append(role)
        ow = discord.PermissionOverwrite
        private = {guild.default_role: ow(view_channel=False), **{r: ow(view_channel=True) for r in staff}}
        readonly = {guild.default_role: ow(send_messages=False, create_public_threads=False, create_private_threads=False, send_messages_in_threads=False), staff[0]: ow(send_messages=True)}
        channels = await guild.fetch_channels()
        for category, perms, names in [('✦ Start here', readonly, ['welcome-and-rules', 'get-zyrox']), ('☄ Release desk', readonly, ['updates', 'whats-new']), ('✧ Community', {}, ['general', 'showcase', 'Lounge']), ('⚙ Workshop', {}, ['launcher-help', 'ideas']), ('🔒 Staff', private, ['staff-room', 'mod-log'])]:
            cat = discord.utils.get(channels, name=category, type=discord.ChannelType.category)
            if cat is None:
                cat = await guild.create_category(category, overwrites=perms)
            elif perms and cat.overwrites != perms:
                raise RuntimeError(f'Review permissions on existing category: {category}; not modifying it.')
            for name in names:
                if any(c.name == name and c.category_id == cat.id for c in channels):
                    continue
                if name == 'Lounge':
                    await guild.create_voice_channel(name, category=cat)
                elif category == '⚙ Workshop' and 'COMMUNITY' in guild.features:
                    await guild.create_forum(name, category=cat)
                else:
                    channel = await guild.create_text_channel(name, category=cat)
                    if name == 'updates' and 'COMMUNITY' in guild.features:
                        await channel.edit(type=discord.ChannelType.news)
                    if name == 'get-zyrox':
                        await channel.send('Official APK: https://github.com/argamerzz9582-debug/ZyroxLauncher/releases/latest — never install APKs from DMs.')
                    if name == 'welcome-and-rules':
                        await channel.send('Zyrox mein welcome! Start in #get-zyrox. Respect rakho; no spam, APK mirrors or unsolicited DMs. Never share passwords or login codes.')
        print('Setup complete. Verify permissions and assign trusted staff manually.')


if __name__ == '__main__':
    asyncio.run(main())
