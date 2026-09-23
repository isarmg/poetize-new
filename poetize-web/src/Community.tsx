import {useEffect, useRef, useState, type FormEvent} from 'react';
import {request} from './api';

type Member = {id:number; username:string; avatar:string|null; csrf_token:string};
type Friend = {id:number; username:string; avatar:string|null; remark:string|null};
type Group = {id:number; group_name:string; avatar:string|null; introduction:string|null; master_user_id:number};
type PendingUser = {id:number;username:string;avatar:string|null};
type ChatMessage = {id:number; from_id:number; to_id?:number; group_id?:number; content:string; create_time?:string|null; kind?:string};
type Room = {kind:'direct'|'group'; id:number; title:string};

export function Community() {
  const [member,setMember]=useState<Member|null>(null);
  const [login,setLogin]=useState({account:'',password:''});
  const [registering,setRegistering]=useState(false);
  const [friends,setFriends]=useState<Friend[]>([]);
  const [groups,setGroups]=useState<Group[]>([]);
  const [pendingFriends,setPendingFriends]=useState<PendingUser[]>([]);
  const [pendingGroup,setPendingGroup]=useState<PendingUser[]>([]);
  const [room,setRoom]=useState<Room|null>(null);
  const [messages,setMessages]=useState<ChatMessage[]>([]);
  const [draft,setDraft]=useState('');
  const [friendId,setFriendId]=useState('');
  const [groupName,setGroupName]=useState('');
  const [groupId,setGroupId]=useState('');
  const [userSearch,setUserSearch]=useState('');
  const [groupSearch,setGroupSearch]=useState('');
  const [foundUsers,setFoundUsers]=useState<PendingUser[]>([]);
  const [foundGroups,setFoundGroups]=useState<Group[]>([]);
  const [error,setError]=useState('');
  const socket=useRef<WebSocket|null>(null);
  const currentRoom=useRef<Room|null>(null);
  const bottom=useRef<HTMLDivElement|null>(null);
  useEffect(()=>{currentRoom.current=room;},[room]);

  async function refreshLists() {
    const [nextFriends,nextGroups,nextRequests]=await Promise.all([
      request<Friend[]>('/api/v2/im/friends'),request<Group[]>('/api/v2/im/groups'),request<PendingUser[]>('/api/v2/im/friends/requests'),
    ]);
    setFriends(nextFriends);setGroups(nextGroups);setPendingFriends(nextRequests);
  }
  useEffect(()=>{
    void request<Member>('/api/v2/members/session').then(value=>setMember(value)).catch(()=>{});
  },[]);
  useEffect(()=>{
    if (!member) return;
    void refreshLists().catch(()=>setError('联系人加载失败'));
    const scheme=window.location.protocol==='https:'?'wss':'ws';
    let active=true;
    let retry:number|undefined;
    function connect(){
      if(!active)return;
      const ws=new WebSocket(`${scheme}://${window.location.host}/socket`);
      socket.current=ws;
      ws.onmessage=event=>{
        try {
          const message=JSON.parse(event.data) as ChatMessage;
          const selected=currentRoom.current;
          if (message.kind==='resync'&&selected) {
            const path=selected.kind==='direct'?`/api/v2/im/direct/${selected.id}`:`/api/v2/im/groups/${selected.id}/messages`;
            void request<ChatMessage[]>(path).then(setMessages).catch(()=>setError('聊天记录同步失败'));
            return;
          }
          const matches=selected&&(message.kind==='direct'&&selected.kind==='direct'&&(message.from_id===selected.id||message.to_id===selected.id)||message.kind==='group'&&selected.kind==='group'&&message.group_id===selected.id);
          if(matches) setMessages(current=>current.some(item=>item.id===message.id)?current:[...current,message]);
        } catch { /* Ignore malformed transport frames. */ }
      };
      ws.onclose=()=>{if(socket.current===ws)socket.current=null;if(active)retry=window.setTimeout(connect,2000);};
    }
    connect();
    return ()=>{active=false;window.clearTimeout(retry);socket.current?.close();socket.current=null;};
  },[member?.id]);
  useEffect(()=>{
    if (!room) return;
    const path=room.kind==='direct'?`/api/v2/im/direct/${room.id}`:`/api/v2/im/groups/${room.id}/messages`;
    void request<ChatMessage[]>(path).then(setMessages).catch(()=>setError('聊天记录加载失败'));
    if(room.kind==='group'&&groups.find(group=>group.id===room.id)?.master_user_id===member?.id)void request<PendingUser[]>(`/api/v2/im/groups/${room.id}/requests`).then(setPendingGroup).catch(()=>setPendingGroup([]));else setPendingGroup([]);
  },[room?.id,room?.kind,groups,member?.id]);
  useEffect(()=>{bottom.current?.scrollIntoView({behavior:'smooth'});},[messages]);

  async function signIn(event:FormEvent) {
    event.preventDefault();setError('');
    try {const path=registering?'/api/v2/members/register':'/api/v2/members/login';const payload=registering?{username:login.account,password:login.password}:login;const session=await request<Member>(path,{method:'POST',body:JSON.stringify(payload)});setMember(session);setLogin({account:'',password:''});}
    catch (reason) {setError(reason instanceof Error?reason.message:'操作失败');}
  }
  async function signOut() {
    if (!member) return;
    try {await request('/api/v2/members/logout',{method:'POST'},member.csrf_token);setMember(null);setRoom(null);setFriends([]);setGroups([]);}
    catch {setError('退出失败，请重试');}
  }
  function send(event:FormEvent) {
    event.preventDefault();if(!room||!draft.trim()||!socket.current||socket.current.readyState!==WebSocket.OPEN)return;
    socket.current.send(JSON.stringify({kind:room.kind,to_id:room.kind==='direct'?room.id:undefined,group_id:room.kind==='group'?room.id:undefined,content:draft.trim()}));
    setDraft('');
  }
  async function friendRequest(event:FormEvent) {
    event.preventDefault();if(!member)return;
    try {await request(`/api/v2/im/friends/${Number(friendId)}/request`,{method:'POST'},member.csrf_token);setFriendId('');setError('好友申请已发送');}
    catch {setError('好友申请发送失败');}
  }
  async function createGroup(event:FormEvent) {
    event.preventDefault();if(!member)return;
    try {await request('/api/v2/im/groups',{method:'POST',body:JSON.stringify({name:groupName,in_type:0})},member.csrf_token);setGroupName('');await refreshLists();}
    catch {setError('创建群组失败');}
  }
  async function joinGroup(event:FormEvent) {
    event.preventDefault();if(!member)return;
    try {await request(`/api/v2/im/groups/${Number(groupId)}/join`,{method:'POST'},member.csrf_token);setGroupId('');await refreshLists();}
    catch {setError('加入群组失败');}
  }
  async function acceptFriend(id:number){if(!member)return;try{await request(`/api/v2/im/friends/${id}/accept`,{method:'POST'},member.csrf_token);await refreshLists();}catch{setError('接受好友申请失败');}}
  async function declineFriend(id:number){if(!member)return;try{await request(`/api/v2/im/friends/${id}/decline`,{method:'POST'},member.csrf_token);await refreshLists();}catch{setError('拒绝好友申请失败');}}
  async function removeFriend(id:number){if(!member||!window.confirm('确定解除好友关系？'))return;try{await request(`/api/v2/im/friends/${id}`,{method:'DELETE'},member.csrf_token);if(room?.kind==='direct'&&room.id===id)setRoom(null);await refreshLists();}catch{setError('解除好友关系失败');}}
  async function renameFriend(friend:Friend){if(!member)return;const remark=window.prompt('好友备注，留空可清除',friend.remark||'');if(remark===null)return;try{await request(`/api/v2/im/friends/${friend.id}/remark`,{method:'PUT',body:JSON.stringify({remark:remark.trim()||null})},member.csrf_token);await refreshLists();}catch{setError('好友备注保存失败');}}
  async function leaveGroup(id:number){if(!member||!window.confirm('确定退出这个群组？'))return;try{await request(`/api/v2/im/groups/${id}/leave`,{method:'POST'},member.csrf_token);if(room?.kind==='group'&&room.id===id)setRoom(null);await refreshLists();}catch(reason){setError(reason instanceof Error?reason.message:'退出群组失败');}}
  async function approveGroup(id:number){if(!member||!room||room.kind!=='group')return;try{await request(`/api/v2/im/groups/${room.id}/members/${id}/approve`,{method:'POST'},member.csrf_token);setPendingGroup(current=>current.filter(item=>item.id!==id));}catch{setError('群成员审核失败');}}
  async function searchForUsers(event:FormEvent){event.preventDefault();try{setFoundUsers(await request<PendingUser[]>(`/api/v2/im/users?search=${encodeURIComponent(userSearch)}`));setError('');}catch{setError('用户搜索失败');}}
  async function searchForGroups(event:FormEvent){event.preventDefault();try{setFoundGroups(await request<Group[]>(`/api/v2/im/groups/discover?search=${encodeURIComponent(groupSearch)}`));setError('');}catch{setError('群组搜索失败');}}
  async function requestFoundFriend(id:number){if(!member)return;try{await request(`/api/v2/im/friends/${id}/request`,{method:'POST'},member.csrf_token);setError('好友申请已发送');}catch{setError('好友申请失败');}}
  async function joinFoundGroup(id:number){if(!member)return;try{await request(`/api/v2/im/groups/${id}/join`,{method:'POST'},member.csrf_token);await refreshLists();setError('入群申请已提交');}catch{setError('加入群组失败');}}

  return <div className="community-page"><header className="public-header"><a className="brand" href="/">POETIZE</a><nav><a href="/">返回网站</a>{member&&<a href="/user">我的资料</a>}{member&&<button onClick={()=>void signOut()}>退出登录</button>}</nav></header>
    {!member?<div className="community-login"><p className="eyebrow">COMMUNITY</p><h1>聊天室</h1><p>使用站点账号，与朋友聊天并加入群组。</p><form onSubmit={event=>void signIn(event)}><label>{registering?'用户名':'用户名、邮箱或手机号'}<input required minLength={registering?3:undefined} maxLength={registering?32:undefined} autoComplete="username" value={login.account} onChange={event=>setLogin({...login,account:event.target.value})}/></label><label>密码<input required type="password" minLength={registering?12:undefined} autoComplete={registering?'new-password':'current-password'} value={login.password} onChange={event=>setLogin({...login,password:event.target.value})}/></label><button type="submit">{registering?'注册并登录':'登录'}</button></form><button className="text-action" onClick={()=>{setRegistering(value=>!value);setError('');}}>{registering?'已有账号？返回登录':'没有账号？注册'}</button>{error&&<p role="alert">{error}</p>}</div>:
    <main className="community-layout"><aside className="community-side"><div className="community-member"><strong>{member.username}</strong><small>在线</small></div><h2>好友</h2>{friends.map(friend=><div className="community-contact" key={friend.id}><button className={room?.kind==='direct'&&room.id===friend.id?'selected':''} onClick={()=>setRoom({kind:'direct',id:friend.id,title:friend.remark||friend.username})}>{friend.remark||friend.username}</button><details><summary aria-label={`管理好友 ${friend.username}`}>⋯</summary><button onClick={()=>void renameFriend(friend)}>修改备注</button><button onClick={()=>void removeFriend(friend.id)}>删除好友</button></details></div>)}{pendingFriends.length>0&&<><h2>好友申请</h2>{pendingFriends.map(user=><div className="pending-request" key={user.id}><span>{user.username}</span><button onClick={()=>void acceptFriend(user.id)}>接受</button><button onClick={()=>void declineFriend(user.id)}>拒绝</button></div>)}</>}<h2>群组</h2>{groups.map(group=><div className="community-contact" key={group.id}><button className={room?.kind==='group'&&room.id===group.id?'selected':''} onClick={()=>setRoom({kind:'group',id:group.id,title:group.group_name})}>{group.group_name}</button>{group.master_user_id!==member.id&&<button className="community-leave" aria-label={`退出群组 ${group.group_name}`} onClick={()=>void leaveGroup(group.id)}>退出</button>}</div>)}<details><summary>添加好友</summary><form onSubmit={event=>void friendRequest(event)}><input type="number" min="1" placeholder="用户 ID" value={friendId} onChange={event=>setFriendId(event.target.value)} required/><button type="submit">发送申请</button></form></details><details><summary>创建群组</summary><form onSubmit={event=>void createGroup(event)}><input placeholder="群组名称" value={groupName} onChange={event=>setGroupName(event.target.value)} required maxLength={32}/><button type="submit">创建</button></form></details><details><summary>加入群组</summary><form onSubmit={event=>void joinGroup(event)}><input type="number" placeholder="群组 ID" value={groupId} onChange={event=>setGroupId(event.target.value)} required/><button type="submit">加入</button></form></details><details><summary>查找用户</summary><form onSubmit={event=>void searchForUsers(event)}><input minLength={2} maxLength={64} placeholder="用户名" value={userSearch} onChange={event=>setUserSearch(event.target.value)} required/><button type="submit">查找</button></form>{foundUsers.map(user=><div className="pending-request" key={user.id}><span>{user.username} #{user.id}</span><button onClick={()=>void requestFoundFriend(user.id)}>申请</button></div>)}</details><details><summary>浏览群组</summary><form onSubmit={event=>void searchForGroups(event)}><input minLength={2} maxLength={64} placeholder="群组名称" value={groupSearch} onChange={event=>setGroupSearch(event.target.value)} required/><button type="submit">查找</button></form>{foundGroups.map(group=><div className="pending-request" key={group.id}><span>{group.group_name} #{group.id}</span><button onClick={()=>void joinFoundGroup(group.id)}>加入</button></div>)}</details></aside><section className="chat-panel">{room?<><header><h1>{room.title}</h1><span>{room.kind==='group'?'群聊':'私信'}</span></header>{pendingGroup.length>0&&<div className="group-requests">入群申请：{pendingGroup.map(user=><button key={user.id} onClick={()=>void approveGroup(user.id)}>{user.username} · 同意</button>)}</div>}<div className="chat-log" role="log" aria-live="polite">{messages.map(message=><div key={`${message.kind||room.kind}-${message.id}`} className={message.from_id===member.id?'bubble own':'bubble'}><small>{message.from_id===member.id?'我':`用户 ${message.from_id}`}</small><p>{message.content}</p></div>)}<div ref={bottom}/></div><form className="chat-compose" onSubmit={send}><input value={draft} maxLength={1024} placeholder="输入消息…" onChange={event=>setDraft(event.target.value)} /><button type="submit">发送</button></form></>:<div className="chat-empty">选择好友或群组，开始聊天。</div>}</section>{error&&<p className="community-error" role="alert">{error}</p>}</main>}
  </div>;
}
