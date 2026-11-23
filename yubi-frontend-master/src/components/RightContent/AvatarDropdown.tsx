import {
  confirmBindEmailUsingPOST,
  getLoginUserUsingGET,
  requestBindEmailCodeUsingPOST,
  updateMyUserUsingPOST,
  userLogoutUsingPOST,
} from '@/services/yubi/userController';
import {
  LogoutOutlined,
  MailOutlined,
  SafetyOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useEmotionCss } from '@ant-design/use-emotion-css';
import { history, useModel } from '@umijs/max';
import { Button, Form, Input, message, Modal, Space, Spin, Tag } from 'antd';
import { stringify } from 'querystring';
import type { MenuInfo } from 'rc-menu/lib/interface';
import React, { useCallback, useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import HeaderDropdown from '../HeaderDropdown';

export type GlobalHeaderRightProps = {
  menu?: boolean;
  children?: React.ReactNode;
};

type BindEmailFormValues = {
  userName?: string;
  email?: string;
  verifyCode?: string;
};

export const AvatarName = () => {
  const { initialState } = useModel('@@initialState');
  const { currentUser } = initialState || {};
  return <span className="anticon">{currentUser?.userName || '我的账号'}</span>;
};

export const AvatarDropdown: React.FC<GlobalHeaderRightProps> = ({ menu, children }) => {
  const [profileModalOpen, setProfileModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [form] = Form.useForm<BindEmailFormValues>();

  useEffect(() => {
    if (cooldown <= 0) return undefined;
    const timer = window.setInterval(() => {
      setCooldown((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooldown]);

  const loginOut = async () => {
    try {
      await userLogoutUsingPOST();
    } catch (_error) {
      // 即使后端会话已过期也继续跳转登录页
    }
    const { search, pathname } = window.location;
    const urlParams = new URL(window.location.href).searchParams;
    const redirect = urlParams.get('redirect');
    if (window.location.pathname !== '/user/login' && !redirect) {
      history.replace({
        pathname: '/user/login',
        search: stringify({
          redirect: pathname + search,
        }),
      });
    }
  };

  const actionClassName = useEmotionCss(({ token }) => {
    return {
      display: 'flex',
      height: '48px',
      marginLeft: 'auto',
      overflow: 'hidden',
      alignItems: 'center',
      padding: '0 8px',
      cursor: 'pointer',
      borderRadius: token.borderRadius,
      '&:hover': {
        backgroundColor: token.colorBgTextHover,
      },
    };
  });

  const { initialState, setInitialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  const openBindEmailModal = useCallback(() => {
    form.setFieldsValue({
      userName: currentUser?.userName,
      email: currentUser?.email,
      verifyCode: '',
    });
    setProfileModalOpen(true);
  }, [currentUser, form]);

  const handleSendBindCode = async () => {
    const email = (form.getFieldValue('email') || '').trim();
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      message.warning('请先输入有效的邮箱地址');
      return;
    }
    setSendingCode(true);
    try {
      const res = await requestBindEmailCodeUsingPOST({ email });
      if (res.code === 0) {
        setCooldown(60);
        if (typeof res.data === 'string' && res.data.startsWith('DEV_FALLBACK:')) {
          const fallbackCode = res.data.slice('DEV_FALLBACK:'.length);
          form.setFieldValue('verifyCode', fallbackCode);
          message.info(
            `本机代理拦截了 SMTP 端口，已启用本地开发兜底验证码：${fallbackCode}（5 分钟内有效）`,
            6,
          );
        } else {
          message.success(`验证码已发送至 ${res.data || email}，请前往邮箱查收（5 分钟内有效）`);
        }
      } else {
        message.error(res.message || '发送验证码失败');
      }
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '发送验证码失败，请重试');
    } finally {
      setSendingCode(false);
    }
  };

  const handleSaveProfile = async () => {
    try {
      const values = await form.validateFields();
      const targetEmail = (values.email || '').trim();
      const emailUnchanged =
        Boolean(currentUser?.email) &&
        currentUser?.email?.trim().toLowerCase() === targetEmail.toLowerCase();

      setSaving(true);
      if (emailUnchanged) {
        const res = await updateMyUserUsingPOST({
          userName: values.userName?.trim() || currentUser?.userName,
        });
        if (res.code !== 0 || !res.data) {
          message.error(res.message || '保存失败');
          return;
        }
      } else {
        const code = (values.verifyCode || '').trim();
        if (!code) {
          message.warning('请先获取并填写邮箱收到的 6 位验证码');
          return;
        }
        const res = await confirmBindEmailUsingPOST({
          email: targetEmail,
          verifyCode: code,
          userName: values.userName?.trim() || currentUser?.userName,
        });
        if (res.code !== 0 || !res.data) {
          message.error(res.message || '邮箱验证绑定失败');
          return;
        }
      }

      const refreshed = await getLoginUserUsingGET();
      if (refreshed.data) {
        flushSync(() => {
          setInitialState((s) => ({ ...s, currentUser: refreshed.data }));
        });
      }
      message.success(emailUnchanged ? '昵称已更新' : '邮箱验证通过，绑定成功');
      setProfileModalOpen(false);
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.response?.data?.message || error?.message || '操作失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const onMenuClick = useCallback(
    (event: MenuInfo) => {
      const { key } = event;
      if (key === 'bindEmail') {
        openBindEmailModal();
        return;
      }
      if (key === 'logout') {
        flushSync(() => {
          setInitialState((s) => ({ ...s, currentUser: undefined }));
        });
        void loginOut();
        return;
      }
      history.push(`/account/${key}`);
    },
    [openBindEmailModal, setInitialState],
  );

  const loading = (
    <span className={actionClassName}>
      <Spin
        size="small"
        style={{
          marginLeft: 8,
          marginRight: 8,
        }}
      />
    </span>
  );

  if (!initialState || !currentUser) {
    return loading;
  }

  const menuItems = [
    ...(menu
      ? [
          {
            key: 'center',
            icon: <UserOutlined />,
            label: '个人中心',
          },
          {
            key: 'settings',
            icon: <SettingOutlined />,
            label: '个人设置',
          },
        ]
      : []),
    {
      key: 'bindEmail',
      icon: <MailOutlined />,
      label: currentUser.email ? '修改绑定邮箱' : '绑定找回邮箱',
    },
    {
      type: 'divider' as const,
    },
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
    },
  ];

  return (
    <>
      <HeaderDropdown
        menu={{
          selectedKeys: [],
          onClick: onMenuClick,
          items: menuItems,
        }}
      >
        {children}
      </HeaderDropdown>
      <Modal
        title="账号设置 · 绑定找回邮箱"
        open={profileModalOpen}
        onOk={handleSaveProfile}
        onCancel={() => setProfileModalOpen(false)}
        confirmLoading={saving}
        okText="验证并保存"
        cancelText="取消"
        destroyOnClose
      >
        <div style={{ marginBottom: 16 }}>
          {currentUser.email ? (
            <Tag color="success">当前已绑定：{currentUser.email}</Tag>
          ) : (
            <Tag color="warning">尚未绑定邮箱，需通过邮箱验证码校验归属权后绑定</Tag>
          )}
        </div>
        <Form form={form} layout="vertical">
          <Form.Item name="userName" label="用户昵称">
            <Input placeholder="请输入昵称（选填）" maxLength={32} />
          </Form.Item>
          <Form.Item
            name="email"
            label="绑定邮箱"
            rules={[
              { required: true, message: '请输入需要绑定的邮箱' },
              { type: 'email', message: '请输入有效的邮箱地址' },
            ]}
          >
            <Input prefix={<MailOutlined />} placeholder="例如：yourname@qq.com" />
          </Form.Item>
          <Form.Item label="邮箱验证码" required>
            <Space.Compact style={{ width: '100%' }}>
              <Form.Item name="verifyCode" noStyle>
                <Input
                  prefix={<SafetyOutlined />}
                  maxLength={6}
                  placeholder="请输入邮箱收到的 6 位验证码（未改邮箱可留空）"
                />
              </Form.Item>
              <Button
                loading={sendingCode}
                disabled={cooldown > 0}
                onClick={handleSendBindCode}
              >
                {cooldown > 0 ? `${cooldown}s 后重试` : '发送验证码'}
              </Button>
            </Space.Compact>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};
