import { GithubOutlined } from '@ant-design/icons';
import { DefaultFooter } from '@ant-design/pro-components';
import '@umijs/max';
import React from 'react';
const Footer: React.FC = () => {
  const defaultMessage = 'Epiphany119';
  const currentYear = new Date().getFullYear();
  return (
    <DefaultFooter
      style={{
        background: 'none',
      }}
      copyright={`${currentYear} ${defaultMessage}`}
      links={[
        {
          key: 'epiph-bi',
          title: 'Epiph-BI',
          href: 'https://github.com/Epiphany119/Epiph-BI',
          blankTarget: true,
        },
        {
          key: 'github',
          title: <GithubOutlined />,
          href: 'https://github.com/Epiphany119/Epiph-BI',
          blankTarget: true,
        },
        {
          key: 'epiph-bi-project',
          title: 'Epiph-BI',
          href: 'https://github.com/Epiphany119/Epiph-BI',
          blankTarget: true,
        },
      ]}
    />
  );
};
export default Footer;
